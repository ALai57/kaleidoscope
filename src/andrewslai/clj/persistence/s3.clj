(ns andrewslai.clj.persistence.s3
  (:require [amazonica.core :as amazon]
            [amazonica.aws.s3 :as s3]
            [andrewslai.clj.persistence.filesystem :as fs]
            [clojure.string :as string]
            [ring.util.http-response :refer [content-type not-found ok
                                             internal-server-error
                                             resource-response]]
            [taoensso.timbre :as log])
  (:import [com.amazonaws.auth
            AWSCredentialsProviderChain
            EnvironmentVariableCredentialsProvider
            ContainerCredentialsProvider
            EC2ContainerCredentialsProviderWrapper]
           [com.amazonaws.auth.profile ProfileCredentialsProvider]
           [java.net URL URLStreamHandler URLConnection URLStreamHandlerFactory URLClassLoader]))

(def CustomAWSCredentialsProviderChain
  (AWSCredentialsProviderChain.
   [^AWSCredentialsProvider (new EnvironmentVariableCredentialsProvider)
    ^AWSCredentialsProvider (new ProfileCredentialsProvider)
    ^AWSCredentialsProvider (new ContainerCredentialsProvider)]))

#_(bean (.getCredentials CustomAWSCredentialsProviderChain))

(defn exception-response
  [{:keys [status-code] :as exception-map}]
  (case status-code
    404 (not-found)
    (internal-server-error "Unknown exception")))

(defrecord S3 [bucket creds]
  fs/FileSystem
  (ls [_ path]
    (log/info "List objects in S3: " path)
    (->> (s3/list-objects-v2 creds
                             {:bucket-name bucket
                              :prefix      path})
         :object-summaries
         (drop 1)
         (map (fn [m] (select-keys m [:key :size :etag])))
         seq))
  (get-file [_ path]
    (log/info "Get object in S3: " path)
    (try
      (-> (s3/get-object creds {:bucket-name bucket :key path})
          :input-stream)
      (catch Exception e
        (println e)
        (exception-response (amazon/ex->map e))))))

(comment

  (import '[com.amazonaws.auth DefaultAWSCredentialsProviderChain])
  (s3/list-buckets)

  (amazon/get-credentials nil)

  (keys (bean (.getCredentials CustomAWSCredentialsProviderChain)))

  (ls (map->S3 {:bucket "andrewslai-wedding"
                :credentials CustomAWSCredentialsProviderChain})
      "media/")

  (get-file (map->S3 {:bucket "andrewslai-wedding"})
            "wedding-index.html")

  (get-file (map->S3 {:bucket "andrewslai-wedding"})
            "media")

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
  (s3/get-object "andrewslai-wedding" "wedding-index.html")
  (s3/get-object CustomAWSCredentialsProviderChain {:bucket-name "andrewslai-wedding"
                                                    :key "wedding-index.html"})
  )
