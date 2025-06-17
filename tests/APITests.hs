module APITests (apiTests) where

import Test.Hspec
import Test.HUnit (assertFailure, assertBool, (@?=)) -- For assertions, or use Hspec's own

import Control.Monad.Reader (runReaderT, ask)
import Data.Aeson (Value(..), object, (.=))
import qualified Data.HashMap.Strict as HM
import Data.Text (Text, pack)
import qualified Data.Text as T
import Data.Maybe (isJust, fromJust)

import Simplex.Chat.Controller
import Simplex.Chat.Config (defaultChatConfig)
import Simplex.Chat.Core (newChatController)
import Simplex.Chat.DB (initDB, simplexChatSchema)
import Simplex.Chat.Options (ChatOpts(..), CoreChatOpts(..), DBOpts(..), defaultTestChatOpts, defaultTestAgentConfig)
import Simplex.Chat.Types (User(..), LocalProfile(..), UserSettings(..), defaultUserSettings, VersionRangeChat(..))
import Simplex.Chat.Store.Profiles (Profile(..), createNewUser)
import Simplex.Chat.Store.Contacts (createContact)
import Simplex.Chat.Store.Messages -- For message creation and types like ChatItem, CIFile etc.
import Simplex.Chat.Store.Shared (SharedStore(..)) -- For ChatStore
import Simplex.Chat.Protocol (XEvent(..), MsgContainer(..), ExtMsgContent(..), currentChatVersion, MsgContent(..))
import Simplex.Chat.Messages.CIContent -- For CIContent itself, CIFile(..), FileTransferId
import Simplex.Chat.Library.Commands (execChatCommand')
import Simplex.Messaging.Agent.Store.Common (DBStore(..))
import Simplex.Messaging.Agent.Protocol (AgentUserId(..))
import qualified Simplex.Messaging.Crypto.File as CF -- For CryptoFile
import Data.Time.Clock (getCurrentTime)
import System.IO.Temp (withSystemTempDirectory)
import Simplex.Messaging.Agent.Env.SQLite (AgentConfig(..))
import Simplex.Chat.Store.Contacts (deleteContact) -- For cleanup if needed
import Simplex.Chat.DB.Instances () -- For DBStore FromField/ToField if not already covered

-- A simplified test environment setup
data TestEnv = TestEnv
  { teController :: ChatController
  , teUser :: User
  , teChatStore :: DBStore
  , teAgentStore :: DBStore
  }

-- Simplified withTestEnv - in a real scenario, this would use more from ChatTests.Utils
withTestEnv :: (TestEnv -> IO ()) -> IO ()
withTestEnv action =
  withSystemTempDirectory "simplex-test-db" $ \tmpDir -> do
    let dbPath = tmpDir ++ "/chat.db"
        agentDbPath = tmpDir ++ "/agent.db"
        dbo = DBOpts {dbPath = Just dbPath, dbWAL = True, dbMaxConnections = 1}
        agentDbo = dbo {dbPath = Just agentDbPath}

    cs <- DBStore <$> initDB dbo simplexChatSchema
    as <- DBStore <$> initDB agentDbo (agentSchema CurrentMigrations)

    let chatCfg = defaultChatConfig (defaultTestAgentConfig agentDbPath) CurrentChatVersion
        chatOpts = defaultTestChatOpts

    cc <- newChatController (ChatDatabase cs as) Nothing chatCfg chatOpts False

    let testUserProfile = Profile "testuser" "Test User" Nothing Nothing Nothing
    eUser <- runReaderT (execChatCommand' (CreateActiveUser (NewUser testUserProfile False))) cc
    case eUser of
      Right (CRActiveUser user) -> do
        _ <- runReaderT (execChatCommand' APIActivateChat{restoreChat=False}) cc
        action (TestEnv cc user cs as)
      _ -> error "Failed to create test user"

apiTests :: Spec
apiTests = describe "API Tests" $ do
  testMessagesInRange

import qualified Simplex.Messaging.Agent.Store.DB as DB
import Simplex.Messaging.Encoding.String (strEncode) -- For encoding enums to text for DB

mockCreateContactAndFileMessage :: TestEnv -> IO (Contact, ChatItemId, FileTransferId)
mockCreateContactAndFileMessage TestEnv{teController, teUser, teChatStore} = do
  let cc = teController
      user = teUser
      chatDB = unDBStore teChatStore -- Get the raw DB.Connection

  -- 1. Create a Contact
  let contactProfile = Profile "filecontact" "File Contact" Nothing Nothing Nothing
  (contact, _) <- liftIO $ runReaderT (Simplex.Chat.Store.Contacts.createContact contactProfile) cc
  let contactId = Simplex.Chat.Store.Profiles.userId contact

  -- 2. Create a SndMessage and ChatItem (without file initially)
  now <- getCurrentTime
  let sharedMsgId = SharedMsgId "test-shared-msg-id-for-file"
      -- msgId for SndMessage is usually retrieved from DB after inserting into messages table.
      -- For this mock, we might not need a real message record if not strictly enforced by createNewSndChatItem's foreign keys.
      -- Let's create a minimal SndMessage.
      sndMsg = SndMessage { msgId = 1, sharedMsgId = sharedMsgId, msgBody = "file_message_body_mock" }

      msgContent = MCFile "test.jpg" -- Text content for the file message
      ciContent = CISndMsgContent msgContent

  chatItemIdVal <- liftIO $ DB.withTransaction chatDB $ \db ->
    createNewSndChatItem db user (CDDirectSnd contact) Nothing sndMsg ciContent Nothing Nothing Nothing False now

  -- 3. Insert into 'files' table to associate a file with this ChatItem
  let fileOriginalName = "test.jpg"
      fileLocalPath = "test_files/test.jpg" -- Relative path for storage
      fileSizeVal = 1024
      fileStatusVal = CIFSSndComplete -- Mark as complete for testing download
      fileProtocolVal = FPLocal -- Simulate a locally "sent" file for simplicity
      chunkSizeVal = 65536

  -- Ensure the dummy file path directory exists if needed for other tests, though not strictly for this one as we don't read the file here.
  -- liftIO $ createDirectoryIfMissing True (takeDirectory $ общегосударственнойFilePath fileLocalPath)

  fileTransferIdVal <- liftIO $ DB.withTransaction chatDB $ \db -> do
    DB.execute db
      "INSERT INTO files (user_id, contact_id, chat_item_id, file_name, file_path, file_size, ci_file_status, protocol, created_at, updated_at, chunk_size) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
      ( Simplex.Chat.Store.Profiles.userId user, Just contactId, Just chatItemIdVal, fileOriginalName, fileLocalPath, fileSizeVal
      , decodeLatin1 $ strEncode fileStatusVal -- Correctly encode status to Text
      , decodeLatin1 $ strEncode fileProtocolVal -- Correctly encode protocol to Text
      , now, now, chunkSizeVal
      )
    DB.insertedRowId db

  pure (contact, chatItemIdVal, fileTransferIdVal)


testMessagesInRange :: Spec
testMessagesInRange = describe "/api/v1/messages_in_range" $ do
  it "response includes file_download_url for file messages" $
    withTestEnv $ \testEnv@TestEnv{teController} -> do
      (contact, targetItemId, targetFileId) <- mockCreateContactAndFileMessage testEnv
      let chatRef = ChatRef CTDirect (Simplex.Chat.Store.Profiles.userId contact)

      let request = GetMessagesInRangeRequest chatRef (CPLast 10)
          apiCmd = APIGetMessagesInRange request

      eresponse <- liftIO $ runReaderT (execChatCommand' apiCmd) teController

      case eresponse of
        Left err -> assertFailure $ "API command failed: " ++ show err
        Right (CRMessagesInRange apiResp) -> do
          let msgsWithUrls = messages apiResp

          -- Attempt to find the message that should have the file
          let foundFileMsg = find (\m ->
                case item m of
                  Object obj ->
                    case HM.lookup "meta" obj >>= \(Object metaObj) -> HM.lookup "itemId" metaObj of
                      Just (Number n) -> round n == targetItemId
                      _ -> False
                  _ -> False
              ) msgsWithUrls

          case foundFileMsg of
            Just fm -> do
              liftIO $ putStrLn $ "Found message JSON: " ++ show (item fm) -- Debugging
              liftIO $ putStrLn $ "File download URL from response: " ++ show (file_download_url fm) -- Debugging

              assertBool "file_download_url should be present" (isJust $ file_download_url fm)
              let url = fromJust $ file_download_url fm
                  expectedUrlPart = T.pack $ "/api/v1/download_file/direct/" <> show (Simplex.Chat.Store.Profiles.userId contact) <> "/" <> show targetItemId <> "/" <> show targetFileId
              assertBool ("URL (" <> T.unpack url <> ") should contain: " <> T.unpack expectedUrlPart) (expectedUrlPart `T.isInfixOf` url)
            Nothing -> do
              -- Debug: Print all messages if not found
              liftIO $ putStrLn "Target message not found. Available messages:"
              mapM_ (liftIO . print . item) msgsWithUrls
              assertFailure $ "File message with itemId " ++ show targetItemId ++ " not found in response."
        Right other -> assertFailure $ "Unexpected response type: " ++ show other

```

And modify `tests/Test.hs` to include these tests. I'll need to see its structure first.
