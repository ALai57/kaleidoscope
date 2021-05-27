(ns andrewslai.clj.persistence.filesystem)

(defprotocol FileSystem
  (ls [_ path] "Like the unix `ls` command")
  (get-file [_ path] "Retrieve a single file")
  (put-file [_ path]))
