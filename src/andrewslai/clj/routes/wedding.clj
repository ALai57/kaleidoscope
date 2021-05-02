(ns andrewslai.clj.routes.wedding
  (:require [amazonica.core :as amazon]
            [amazonica.aws.s3 :as s3]
            [amazonica.aws.s3transfer :as s3t]
            [andrewslai.clj.routes.admin :as admin]
            [clojure.string :as string]
            [compojure.api.sweet :refer [context GET]]
            [ring.util.http-response :refer [not-found ok internal-server-error]]
            [spec-tools.swagger.core :as swagger]
            [taoensso.timbre :as log]))

(def WEDDING-BUCKET
  "andrewslai-wedding")

(def MEDIA-FOLDER
  "media")

(defn s3-path [xs]
  (string/join "/" xs))

(defn exception-response
  [{:keys [status-code] :as exception-map}]
  (case status-code
    404 (not-found)
    (internal-server-error "Unknown exception")))

(def wedding-routes
  (context "/wedding" []
    :coercion :spec
    :components []
    :tags ["wedding"]

    (GET "/" []
      :swagger {:summary     "Landing page"
                :description (str "This landing page is the ui for viewing and"
                                  " uploading wedding media.")
                :produces    #{"text/html"}
                :responses   {200 {:description "Landing page for wedding"
                                   :schema      any?}}}
      (-> (s3/get-object WEDDING-BUCKET "index.html")
          :input-stream
          slurp))

    (context "/media" []
      (GET "/" []
        :swagger {:summary   "Retrieve a list of all wedding media"
                  :produces  #{"application/json"}
                  :responses {200 {:description "List of all wedding media"
                                   :schema      any?}}}
        (->> (s3/list-objects-v2 {:bucket-name WEDDING-BUCKET
                                  :prefix      (str MEDIA-FOLDER "/")})
             :object-summaries
             (drop 1)
             (map (fn [m] (select-keys m [:key :size :etag])))))

      (GET "/:id" [id]
        :swagger {:summary     "Retrieve a picture or video"
                  :description "Retrieve an object from the media/ folder"
                  :produces    #{"image/png" "image/svg"}
                  :responses   {200 {:description "S3 object"
                                     :schema      any?}}}
        (try
          (-> (s3/get-object WEDDING-BUCKET (s3-path [MEDIA-FOLDER id]))
              :input-stream)
          (catch Exception e
            (exception-response (amazon/ex->map e))))))))

(comment
  (s3-path [MEDIA-FOLDER "something.ptg"])

  (try
    (-> (s3/get-object WEDDING-BUCKET (s3-path [MEDIA-FOLDER "id"]))
        :input-stream)
    (catch Exception e
      (amazon/ex->map e)))

  (s3/list-objects-v2 {:bucket-name WEDDING-BUCKET
                       :prefix      (str MEDIA-FOLDER "/")})

  (s3/get-object WEDDING-BUCKET (s3-path [MEDIA-FOLDER "SOMETHING"]))

  )
