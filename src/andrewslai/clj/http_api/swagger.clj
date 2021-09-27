(ns andrewslai.clj.http-api.swagger
  (:require [andrewslai.clj.utils :as utils]
            [compojure.api.middleware :as mw]
            [compojure.api.swagger :as swag]
            [compojure.api.sweet :refer [GET routes undocumented]]
            [ring.swagger.common :as rsc]
            [ring.swagger.middleware :as rsm]
            [ring.swagger.swagger-ui :as swagger-ui]
            [ring.swagger.swagger2 :as swagger2]
            [ring.util.http-response :refer [ok]]
            [spec-tools.core :as st-core]
            [spec-tools.swagger.core :as st]))

(def example-data-2
  {:andrewslai.article/article {:summary "An example article"
                                :value {:article_id 10
                                        :article_tags "thoughts"
                                        :article_url "my-test-article"
                                        :author "Andrew Lai"
                                        :content "<h1>Hello world!</h1>"
                                        :timestamp "2020-10-28T00:00:00"
                                        :title "My test article"}}})

(defn extract-specs [swagger]
  (reduce (fn [acc [_ {{schemas :schemas} :components :as x}]]
            (if schemas
              (conj acc schemas)
              acc))
          {}
          (mapcat second (:paths swagger))))

(defn specs->components [swagger-specs]
  (reduce-kv (fn [acc k v]
               (assoc acc
                      k (-> v st-core/create-spec st/transform)))
             {}
             swagger-specs))

(def swagger-ui-routes
  (routes
    (undocumented
     (swagger-ui/swagger-ui {:path "/swagger"
                             :swagger-docs "/swagger.json"}))
    (GET "/swagger.json" req
      {:summary "Return a swagger 3.0.2 spec"
       :produces #{"application/json"}}
      (let [runtime-info1 (mw/get-swagger-data req)
            runtime-info2 (rsm/get-swagger-data req)
            base-path     {:basePath (swag/base-path req)}
            options       (:compojure.api.request/ring-swagger req)
            paths         (:compojure.api.request/paths req)
            swagger       (apply rsc/deep-merge
                                 (keep identity [base-path
                                                 paths
                                                 runtime-info1
                                                 runtime-info2]))
            spec          (st/swagger-spec
                           (swagger2/swagger-json swagger options))]
        (-> spec
            (assoc :openapi "3.0.2"
                   :info {:title       "andrewslai"
                          :description "My personal website"}
                   :components
                   {:schemas (-> swagger
                                 extract-specs
                                 specs->components)
                    :examples (reduce-kv (fn [acc k v]
                                           (assoc acc (name k) v))
                                         {}
                                         example-data-2)})
            (dissoc :swagger)
            ok)))))
