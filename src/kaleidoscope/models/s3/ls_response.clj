(ns kaleidoscope.models.s3.ls-response
  (:require [clojure.string :as string]))

(def FOLDER-DELIMITER "/")

(defn folder?
  ([s]
   (folder? s FOLDER-DELIMITER))
  ([s delim]
   (= delim (str (last s)))))

(defn extract-name
  [path s]
  (let [x (last (string/split s (re-pattern FOLDER-DELIMITER)))]
    (cond
      (= path s)  ""
      (folder? s) (str x FOLDER-DELIMITER)
      :else       x)))

(defn summary->file
  "Maps a cognitect aws-api S3 object summary (PascalCase keys) to a filesystem
  metadata map."
  [path {:keys [Key] :as summary}]
  (assoc summary
         :name (extract-name path Key)
         :path Key
         :type (if (folder? Key)
                 :directory
                 :file)))

(defn prefix->file
  "Maps a cognitect aws-api CommonPrefixes entry {:Prefix \"...\"} to a
  filesystem metadata map."
  [path {:keys [Prefix]}]
  {:name (extract-name path Prefix)
   :path Prefix
   :type :directory})

(comment
  ;; Example cognitect aws-api ListObjectsV2 response shape:
  (def list-objects-response
    {:Contents       [{:Key          "public/"
                       :Size         0
                       :StorageClass "STANDARD"
                       :ETag         "\"d41d8cd98f00b204e9800998ecf8427e\""}]
     :KeyCount       4
     :IsTruncated    false
     :Delimiter      "/"
     :Name           "andrewslai-wedding"
     :CommonPrefixes [{:Prefix "public/assets/"}
                      {:Prefix "public/css/"}
                      {:Prefix "public/images/"}]
     :MaxKeys        1000
     :Prefix         "public/"}))
