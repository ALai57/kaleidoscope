(ns andrewslai.clj.persistence.filesystem)

;; TODO: Change the folder structure to have `/filesystem/impl-name.clj`
(defprotocol FileSystem
  (ls [_ path] "Like the unix `ls` command")
  (get-file [_ path] "Retrieve a single file")
  (put-file [_ path input-stream metadata] "Put a file")
  (get-protocol [_] "Retrieve the protocol (e.g. `http`) the FileSystem uses"))
