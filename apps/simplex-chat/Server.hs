{-# LANGUAGE DeriveAnyClass #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE DuplicateRecordFields #-}
{-# LANGUAGE FlexibleContexts #-}
{-# LANGUAGE FlexibleInstances #-}
{-# LANGUAGE GADTs #-}
{-# LANGUAGE LambdaCase #-}
{-# LANGUAGE NamedFieldPuns #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TemplateHaskell #-}
{-# LANGUAGE UndecidableInstances #-}

module Server where

import Control.Monad
import Control.Monad.Except
import Control.Monad.Reader
import Data.Aeson (FromJSON, ToJSON (..))
import qualified Data.Aeson as J
import qualified Data.Aeson.TH as JQ
import Data.Bifunctor (first)
import Data.Text (Text)
import Data.Text.Encoding (encodeUtf8)
import GHC.Generics (Generic)
import Network.Socket
import qualified Network.WebSockets as WS
import Numeric.Natural (Natural)
import Simplex.Chat.Controller
import Simplex.Chat.Core
import Simplex.Chat.Library.Commands
import Simplex.Chat.Options
import Simplex.Messaging.Parsers (defaultJSON, dropPrefix, taggedObjectJSON)
import Simplex.Messaging.Transport.Server (runLocalTCPServer)
import Simplex.Messaging.Util (raceAny_)
import UnliftIO.Exception
import UnliftIO.STM

import qualified Data.ByteString.Char8 as B
import qualified Data.ByteString.Lazy.Char8 as LBS
import Network.HTTP.Types.URI (parseQueryText)
import Data.Text.Encoding (decodeUtf8, encodeUtf8)
import qualified Data.Text as T
import Data.Aeson (eitherDecodeStrict, encode, ToJSON, FromJSON)
import Control.Monad.Reader (runReaderT)
import Simplex.Chat.Controller (GetMessagesInRangeRequest(..), ChatRef(..), ChatType(..), ChatPagination(..), ChatCommand(APIGetMessagesInRange), ChatResponse(CRMessagesInRange), ChatError)
import Simplex.Chat.Library.Commands (execChatCommand') -- For processChatCommand or execChatCommand'
import Network.HTTP.Types.Status (status200, status400, status404, status500)
import Network.HTTP.Types.Header (hContentType)
import Data.List (find, isPrefixOf)
import Text.Read (readMaybe)
import qualified Data.ByteString.Lazy as LBS
import System.FilePath (takeFileName, takeExtension)
import Simplex.Chat.Files (toFSFilePath)
-- May need Store.Files or Store.Messages to get file path from IDs
import Simplex.Chat.Store.Messages (getAChatItem) -- To verify item and get file details
import Simplex.Chat.Store.Shared (getChatStoreVersionRange) -- for vr
import Simplex.Chat.Messages (AChatItem(..), ChatItem(..), CIFile(..), FileProtocol(..), CIFileStatus(..), SChatType(..), chatItemId', ciFileLoaded)
import Simplex.Messaging.Crypto.File (CryptoFile(..)) -- For CryptoFile filePath
import UnliftIO.Directory (doesFileExist)
import Network.HTTP.Types.Header (hContentDisposition)
import Simplex.Chat.View (getMimeType) -- Assuming a helper like this, or define one

data ChatSrvRequest = ChatSrvRequest {corrId :: Text, cmd :: Text}
  deriving (Generic, FromJSON)

data ChatSrvResponse r = ChatSrvResponse {corrId :: Maybe Text, resp :: CSRBody r}

data CSRBody r = CSRBody {csrBody :: Either ChatError r}

-- backwards compatible encoding, to avoid breaking any chat bots
data ObjChatCmdError = ObjChatCmdError {chatError :: ChatError}

data ObjChatError = ObjChatError {chatError :: ChatError}

$(JQ.deriveToJSON (taggedObjectJSON $ dropPrefix "Obj") ''ObjChatCmdError)

$(JQ.deriveToJSON (taggedObjectJSON $ dropPrefix "Obj") ''ObjChatError)

instance ToJSON (CSRBody ChatResponse) where
  toJSON = toJSON . first ObjChatCmdError . csrBody
  toEncoding = toEncoding . first ObjChatCmdError . csrBody

instance ToJSON (CSRBody ChatEvent) where
  toJSON = toJSON . first ObjChatError . csrBody
  toEncoding = toEncoding . first ObjChatError . csrBody

data AChatSrvResponse = forall r. ToJSON (ChatSrvResponse r) => ACR (ChatSrvResponse r)

$(pure [])

instance ToJSON (CSRBody r) => ToJSON (ChatSrvResponse r) where
  toEncoding = $(JQ.mkToEncoding defaultJSON ''ChatSrvResponse)
  toJSON = $(JQ.mkToJSON defaultJSON ''ChatSrvResponse)

simplexChatServer :: ServiceName -> ChatConfig -> ChatOpts -> IO ()
simplexChatServer chatPort cfg opts =
  simplexChatCore cfg opts . const $ runChatServer defaultChatServerConfig {chatPort}

data ChatServerConfig = ChatServerConfig
  { chatPort :: ServiceName,
    clientQSize :: Natural
  }

defaultChatServerConfig :: ChatServerConfig
defaultChatServerConfig =
  ChatServerConfig
    { chatPort = "5225",
      clientQSize = 1
    }

data ChatClient = ChatClient
  { rcvQ :: TBQueue (Text, ChatCommand),
    sndQ :: TBQueue AChatSrvResponse
  }

newChatServerClient :: Natural -> STM ChatClient
newChatServerClient qSize = do
  rcvQ <- newTBQueue qSize
  sndQ <- newTBQueue qSize
  pure ChatClient {rcvQ, sndQ}

runChatServer :: ChatServerConfig -> ChatController -> IO ()
runChatServer ChatServerConfig {chatPort, clientQSize} cc = do
  started <- newEmptyTMVarIO
  runLocalTCPServer started chatPort $ \sock -> do
    pendingConn <- WS.makePendingConnection sock WS.defaultConnectionOptions
    let reqHead = WS.pendingRequest pendingConn
        path = WS.requestPath reqHead

    let pathSegments = B.split '/' path
    if B.takeWhile (/= '?') path == "/api/v1/messages_in_range" then do
      let queryParams = parseQueryText (B.drop 1 $ B.dropWhile (/= '?') path)
      case parseGetMessagesRequest queryParams of
        Left err -> do
          liftIO $ WS.rejectRequestWith pendingConn WS.defaultRejectRequest {
            WS.rejectCode = statusCode status400,
            WS.rejectMessage = "Bad Request",
            WS.rejectHeaders = [(hContentType, "application/json")],
            WS.rejectBody = LBS.fromStrict $ encodeUtf8 $ T.pack $ "{\"error\": \"" <> err <> "\"}"
          }
        Right getMessagesReq -> do
          let apiCmd = APIGetMessagesInRange getMessagesReq
          eResult <- liftIO $ runReaderT (execChatCommand' apiCmd) cc
          case eResult of
            Right (CRMessagesInRange apiResp) ->
              liftIO $ WS.rejectRequestWith pendingConn WS.defaultRejectRequest {
                WS.rejectCode = statusCode status200,
                WS.rejectMessage = "OK",
                WS.rejectHeaders = [(hContentType, "application/json")],
                WS.rejectBody = encode apiResp
              }
            Right _ -> -- Should not happen for this command
              liftIO $ WS.rejectRequestWith pendingConn WS.defaultRejectRequest {
                WS.rejectCode = statusCode status500,
                WS.rejectMessage = "Internal Server Error",
                WS.rejectHeaders = [(hContentType, "application/json")],
                WS.rejectBody = "{\"error\": \"Unexpected response type from command processing\"}"
              }
            Left chatErr ->
              liftIO $ WS.rejectRequestWith pendingConn WS.defaultRejectRequest {
                WS.rejectCode = statusCode status500,
                WS.rejectMessage = "Error Processing Request",
                WS.rejectHeaders = [(hContentType, "application/json")],
                WS.rejectBody = encode chatErr
              }
    else if "/api/v1/download_file/" `B.isPrefixOf` path && length pathSegments == 7 then do
      -- /api/v1/download_file/:chat_type_str/:chat_id_str/:item_id_str/:file_id_str
      -- pathSegments: ["", "api", "v1", "download_file", chat_type_str, chat_id_str, item_id_str, file_id_str_and_query]
      -- For B.split '/', an initial / leads to an empty first segment.
      -- So, indices are 4, 5, 6, 7.
      let chatTypeStr = decodeUtf8 $ pathSegments !! 4
          chatIdStr = pathSegments !! 5
          itemIdStr = pathSegments !! 6
          fileIdStr = B.takeWhile (/= '?') $ pathSegments !! 7 -- Remove query params if any

      case (parseChatRefFromPath chatTypeStr chatIdStr, readMaybe (B.unpack itemIdStr), readMaybe (B.unpack fileIdStr)) of
        (Right chatRef@(ChatRef _ _), Just itemId, Just fileIdFromUrl) -> do
          eUser <- liftIO $ runReaderT (getActiveUser =<< getChatStoreVersionRange (chatStore cc)) cc
          eVr <- liftIO $ runReaderT getChatStoreVersionRange (chatStore cc)
          case (eUser, eVr) of
            (Right user, Right vr) -> do
              eChatItem <- liftIO $ runReaderT (runExceptT $ getAChatItem (chatStore cc) vr user chatRef itemId) cc
              case eChatItem of
                Right (AChatItem _ _ _ chatItemActual) -> do
                  let mFile = Simplex.Chat.Messages.file chatItemActual
                  case mFile of
                    Just CIFile { fileId = actualFileId, fileName, fileSource = Just (CryptoFile {filePath}), fileStatus } | actualFileId == fileIdFromUrl && ciFileLoaded fileStatus -> do
                      fsFilePath <- liftIO $ toFSFilePath filePath
                      fileExists <- liftIO $ doesFileExist fsFilePath
                      if fileExists then do
                        fileContents <- liftIO $ LBS.readFile fsFilePath
                        let mimeType = getMimeType $ T.pack fileName
                        liftIO $ WS.rejectRequestWith pendingConn WS.defaultRejectRequest {
                          WS.rejectCode = statusCode status200,
                          WS.rejectMessage = "OK",
                          WS.rejectHeaders = [
                            (hContentType, encodeUtf8 mimeType),
                            (hContentDisposition, "attachment; filename=\"" <> encodeUtf8 (T.pack $ takeFileName fileName) <> "\"")
                          ],
                          WS.rejectBody = fileContents
                        }
                      else
                        sendJsonError pendingConn status404 "File not found on server."
                    _ ->
                      sendJsonError pendingConn status404 "File details mismatch or file not loaded."
                Left _ ->
                  sendJsonError pendingConn status404 "Chat item not found or not accessible."
            _ -> sendJsonError pendingConn status500 "Failed to get user or version range."
        _ ->
          sendJsonError pendingConn status400 "Invalid file path components."
    else do
      -- Existing WebSocket logic
      ws <- liftIO $ WS.acceptRequest pendingConn
      c <- atomically $ newChatServerClient clientQSize
      putStrLn "WebSocket client connected"
      raceAny_ [send ws c, client c, output c, receive ws c]
        `finally` clientDisconnected c
  where
    send ws ChatClient {sndQ} =
      forever $
        atomically (readTBQueue sndQ) >>= \(ACR r) -> WS.sendTextData ws (J.encode r)
    client ChatClient {rcvQ, sndQ} = forever $ do
      atomically (readTBQueue rcvQ)
        >>= processCommand
        >>= atomically . writeTBQueue sndQ . ACR
    output ChatClient {sndQ} = forever $ do
      (_, r) <- atomically . readTBQueue $ outputQ cc
      atomically $ writeTBQueue sndQ $ ACR ChatSrvResponse {corrId = Nothing, resp = CSRBody r}
    receive ws ChatClient {rcvQ, sndQ} = forever $ do
      s <- WS.receiveData ws
      case J.decodeStrict' s of
        Just ChatSrvRequest {corrId, cmd} -> do
          putStrLn $ "received command " <> show corrId <> " : " <> show cmd
          case parseChatCommand $ encodeUtf8 cmd of
            Right command -> atomically $ writeTBQueue rcvQ (corrId, command)
            Left e -> sendError (Just corrId) e
        Nothing -> sendError Nothing "invalid request"
      where
        sendError corrId e = atomically $ writeTBQueue sndQ $ ACR ChatSrvResponse {corrId, resp = CSRBody $ chatCmdError e}
    processCommand (corrId, cmd) =
      response <$> runReaderT (runExceptT $ Simplex.Chat.Library.Commands.processChatCommand cmd) cc
      where
        response r = ChatSrvResponse {corrId = Just corrId, resp = CSRBody r}
    clientDisconnected _ = pure ()

-- Helper function to send JSON error responses
sendJsonError :: MonadIO m => WS.PendingConnection -> Status -> String -> m ()
sendJsonError pendingConn status errorMsg =
  liftIO $ WS.rejectRequestWith pendingConn WS.defaultRejectRequest {
    WS.rejectCode = statusCode status,
    WS.rejectMessage = LBS.toStrict $ J.encode statusMessage, -- This should be ByteString, not LBS for rejectMessage if it's short
    WS.rejectHeaders = [(hContentType, "application/json")],
    WS.rejectBody = J.encode $ J.object ["error" J..= errorMsg]
  }
  where
    statusMessage = case statusCode status of
      400 -> "Bad Request"
      404 -> "Not Found"
      500 -> "Internal Server Error"
      _   -> "Error"

-- Helper to parse GetMessagesInRangeRequest from query parameters
parseGetMessagesRequest :: [(B.ByteString, Maybe B.ByteString)] -> Either String GetMessagesInRangeRequest
parseGetMessagesRequest queryParams = do
  chatRef <- parseChatRef queryParams
  pagination <- parseChatPagination queryParams
  pure $ GetMessagesInRangeRequest chatRef pagination

lookupQueryParam :: B.ByteString -> [(B.ByteString, Maybe B.ByteString)] -> Either String B.ByteString
lookupQueryParam name params =
  case find (\(k, _) -> k == name) params of
    Just (_, Just val) -> Right val
    Just (_, Nothing) -> Left $ "Parameter " <> B.unpack name <> " has no value"
    Nothing -> Left $ "Missing parameter: " <> B.unpack name

parseInt64Param :: B.ByteString -> [(B.ByteString, Maybe B.ByteString)] -> Either String Int64
parseInt64Param name params = lookupQueryParam name params >>= \bs ->
  case readMaybe (B.unpack bs) of
    Just i -> Right i
    Nothing -> Left $ "Invalid integer value for parameter " <> B.unpack name

parseIntParam :: B.ByteString -> [(B.ByteString, Maybe B.ByteString)] -> Either String Int
parseIntParam name params = lookupQueryParam name params >>= \bs ->
  case readMaybe (B.unpack bs) of
    Just i -> Right i
    Nothing -> Left $ "Invalid integer value for parameter " <> B.unpack name


parseChatRef :: [(B.ByteString, Maybe B.ByteString)] -> Either String ChatRef
parseChatRef queryParams = do
  typeStr <- B.unpack <$> lookupQueryParam "chat_ref_type" queryParams
  idVal <- parseInt64Param "chat_ref_id" queryParams
  case typeStr of
    "CTDirect" -> Right $ ChatRef CTDirect idVal
    "CTGroup" -> Right $ ChatRef CTGroup idVal
    "CTLocal" -> Right $ ChatRef CTLocal idVal
    _ -> Left $ "Invalid chat_ref_type from query: " <> typeStr

parseChatRefFromPath :: T.Text -> B.ByteString -> Either String ChatRef
parseChatRefFromPath typeStrBS idBs = do
  idVal <- case readMaybe (B.unpack idBs) of
             Just i -> Right i
             Nothing -> Left $ "Invalid id in path: " <> B.unpack idBs
  case T.unpack typeStrBS of
    "direct" -> Right $ ChatRef CTDirect idVal
    "group" -> Right $ ChatRef CTGroup idVal
    "local" -> Right $ ChatRef CTLocal idVal
    _ -> Left $ "Invalid chat_type in path: " <> T.unpack typeStrBS

parseChatPagination :: [(B.ByteString, Maybe B.ByteString)] -> Either String ChatPagination
parseChatPagination queryParams = do
  typeStr <- B.unpack <$> lookupQueryParam "pagination_type" queryParams
  case typeStr of
    "CPLast" -> CPLast <$> parseIntParam "count" queryParams
    "CPAfter" -> CPAfter <$> parseInt64Param "after_id" queryParams <*> parseIntParam "count" queryParams
    "CPBefore" -> CPBefore <$> parseInt64Param "before_id" queryParams <*> parseIntParam "count" queryParams
    "CPAround" -> CPAround <$> parseInt64Param "around_id" queryParams <*> parseIntParam "count" queryParams
    "CPInitial" -> CPInitial <$> parseIntParam "count" queryParams
    _ -> Left $ "Invalid pagination_type: " <> typeStr
