(ns andrewslai.clj.persistence.s3
  (:require [amazonica.core :as amazon]
            [amazonica.aws.s3 :as s3]
            [amazonica.aws.s3transfer :as s3t]
            [clojure.string :as string]
            [ring.util.http-response :refer [content-type not-found ok
                                             internal-server-error
                                             resource-response]]))

(defprotocol FileSystem
  (ls [_ path])
  (get-file [_ path])
  (put-file [_ path]))

(defn exception-response
  [{:keys [status-code] :as exception-map}]
  (case status-code
    404 (not-found)
    (internal-server-error "Unknown exception")))

(defn make-s3
  [config]
  (reify FileSystem
    (ls [_ path]
      (->> (s3/list-objects-v2 {:endpoint (:endpoint config)}
                               {:bucket-name (:bucket-name config)
                                :prefix      path})
           :object-summaries
           (drop 1)
           (map (fn [m] (select-keys m [:key :size :etag])))))
    (get-file [_ path]
      (try
        (-> (s3/get-object (:bucket-name config) path)
            :input-stream)
        (catch Exception e
          (exception-response (amazon/ex->map e)))))))

(comment
  (s3/list-buckets)

  (ls (make-s3 {:bucket-name "andrewslai-wedding"
                :endpoint "us-east-1"})
      "media/")

  (get-file (make-s3 {:bucket-name "andrewslai-wedding"})
            "media/clojure-logo.png")

  (spit "myindex.html" "<h1>HELLO</h1>")

  (s3/put-object :bucket-name "andrewslai-wedding"
                 :key "index.html"
                 :file (clojure.java.io/file "myindex.html"))

  (let [img    "clojure-logo.png"
        home   "/home/andrew/dev/andrewslai/resources/public/images/"]
    (s3/put-object :bucket-name "andrewslai-wedding"
                   :key         (str "media/" img)
                   :file        (clojure.java.io/file (str home img))))

  (slurp (clojure.java.io/resource "public/images/clojure-logo.png"))
  (slurp (clojure.java.io/file "/home/andrew/dev/andrewslai/resources/public/images/clojure-logo.png"))
  (s3/get-object "andrewslai-wedding" "index.html")
  )
