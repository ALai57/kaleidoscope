(ns andrewslai.clj.routes.wedding
  (:require [amazonica.core :as amazon]
            [amazonica.aws.s3 :as s3]
            [amazonica.aws.s3transfer :as s3t]
            [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.persistence.s3 :as fs]
            [andrewslai.clj.routes.admin :as admin]
            [buddy.auth.accessrules :as ar]
            [clojure.string :as string]
            [compojure.api.sweet :refer [context GET]]
            [ring.util.http-response :refer [content-type not-found ok
                                             bad-gateway
                                             internal-server-error
                                             resource-response]]
            [spec-tools.swagger.core :as swagger]
            [taoensso.timbre :as log]))

(def MEDIA-FOLDER
  "media")

(defn require-role
  [role {:keys [identity] :as request}]
  (if (contains? (auth/get-realm-roles identity) role)
    true
    (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                      role
                      (auth/get-realm-roles identity)))))

(def access-rules
  [{:pattern #"^/wedding/media*"
    :handler (partial require-role "wedding")}])

(def wedding-routes
  (context "/wedding" []
    :coercion :spec
    :components [wedding-storage]
    :tags ["wedding"]

    ;; Serve andrewslai.js from S3, instead of from the classpath
    ;; Set up a virtual host to serve the two websites on different handlers
    (GET "/" []
      :swagger {:summary     "Landing page"
                :description (str "This landing page is the ui for viewing and"
                                  " uploading wedding media.")
                :produces    #{"text/html"}
                :responses   {200 {:description "Landing page for wedding"
                                   :schema      any?}}}
      (try
        (-> (ok (fs/get-file wedding-storage "wedding-index.html"))
            (content-type "text/html"))
        (catch Exception e
          (log/error {:msg (.getMessage e)
                      :stack-trace (.getStackTrace e)
                      :cause (.getCause e)
                      :str (.toString e)})
          (bad-gateway (str "Unable to access the requested object: "
                            "`wedding-index.html` using: "
                            (type wedding-storage)))))

      #_(-> (resource-response "wedding-index.html" {:root "public"})
            (content-type "text/html")))

    (context "/media" []
      (GET "/" []
        :swagger {:summary   "Retrieve a list of all wedding media"
                  :produces  #{"application/json"}
                  :responses {200 {:description "List of all wedding media"
                                   :schema      any?}}}
        (try
          (fs/ls wedding-storage (str MEDIA-FOLDER "/"))
          (catch Exception e
            (log/error {:msg (.getMessage e)
                        :stack-trace (.getStackTrace e)
                        :cause (.getCause e)
                        :str (.toString e)})
            (bad-gateway (str "Unable to access the requested repository: "
                              MEDIA-FOLDER
                              " using: "
                              (type wedding-storage))))))

      (GET "/:id" [id]
        :swagger {:summary     "Retrieve a picture or video"
                  :description "Retrieve an object from the media/ folder"
                  :produces    #{"image/png" "image/svg" "image/jpg"}
                  :responses   {200 {:description "S3 object"
                                     :schema      any?}}}
        (try
          (fs/get-file wedding-storage (str MEDIA-FOLDER "/" id))
          (catch Exception e
            (log/error {:msg (.getMessage e)
                        :stack-trace (.getStackTrace e)
                        :cause (.getCause e)
                        :str (.toString e)})
            (bad-gateway (str "Unable to access the requested object: "
                              MEDIA-FOLDER
                              " using persistence: "
                              (type wedding-storage)))))))))

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
