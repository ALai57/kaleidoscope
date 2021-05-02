(ns andrewslai.clj.routes.wedding
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.aws.s3transfer :as s3t]
            [andrewslai.clj.routes.admin :as admin]
            [compojure.api.sweet :refer [context GET]]
            [ring.util.http-response :refer [not-found ok]]
            [spec-tools.swagger.core :as swagger]
            [taoensso.timbre :as log]))

(def wedding-routes
  (context "/wedding" []
    :coercion :spec
    :components [database]
    :tags ["wedding"]

    (GET "/" []
      :swagger {:summary "Landing page"
                :description (str "This landing page is the ui for viewing and"
                                  " uploading wedding media.")
                :produces #{"text/html"}
                :responses {200 {:description "A collection of all articles"
                                 :schema any?}}}
      (-> (s3/get-object "andrewslai-wedding" "index.html")
          :input-stream
          slurp))))
