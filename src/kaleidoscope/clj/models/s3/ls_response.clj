(ns kaleidoscope.clj.models.s3.ls-response
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
  [path {:keys [key] :as summary}]
  (assoc summary
         :name (extract-name path key)
         :path key
         :type (if (folder? key)
                 :directory
                 :file)))

(defn prefix->file
  [path prefix]
  {:name (extract-name path prefix)
   :path prefix
   :type :directory})


(comment
  (def list-objects-response
    {:object-summaries [{:key           "public/",
                         :size          0,
                         ;;:last-modified #clj-time/date-time "2021-05-27T18:30:07.000Z",
                         :storage-class "STANDARD",
                         :bucket-name   "andrewslai-wedding",
                         :etag          "d41d8cd98f00b204e9800998ecf8427e"}],
     :key-count       4,
     :truncated?      false,
     :delimiter       "/",
     :bucket-name     "andrewslai-wedding",
     :common-prefixes ["public/assets/" "public/css/" "public/images/"],
     :max-keys        1000,
     :prefix          "public/"}))
