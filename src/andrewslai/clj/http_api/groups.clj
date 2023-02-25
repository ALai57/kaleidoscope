(ns andrewslai.clj.http-api.groups
  (:require [taoensso.timbre :as log]))


(ns andrewslai.clj.http-api.groups
  (:require [andrewslai.clj.api.groups :as groups-api]
            [andrewslai.clj.api.authentication :as oidc]
            [andrewslai.cljc.specs.articles] ;; Install specs
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.spec.alpha :as s]
            [compojure.api.meta :as compojure-meta]
            [compojure.api.sweet :refer [context GET POST PUT DELETE]]
            [ring.util.http-response :refer [not-found ok conflict]]
            [taoensso.timbre :as log]))

(s/def ::message string?)
(s/def ::error-message (s/keys :req-un [::message]))

(defmethod compojure-meta/restructure-param :swagger
  [_ {request-spec :request :as swagger} acc]
  (let [path (fn [spec] (str "#/components/schemas/" (name spec)))
        ex-path (fn [spec] (str "#/components/examples/" (name spec)))
        x (if request-spec
            (-> swagger
                (assoc :requestBody
                       {:content
                        {"application/json"
                         {:schema
                          {"$ref" (path request-spec)}
                          :examples
                          {(name request-spec) {"$ref" (ex-path request-spec)}}}}})
                (assoc-in [:components :schemas (name request-spec)]
                          {:spec        request-spec
                           :description "Automagically added"}))
            swagger)]
    (assoc-in acc [:info :public :swagger] x)))

(def groups-routes
  ;; HACK: I think the `context` macro may be broken, because it emits an s-exp
  ;; with let-bindings out of order: +compojure-api-request+ is referred to
  ;; before it is bound. This means that, to fix the bug, you'd need to either
  ;; reverse the order of the emitted bindings, or do what I did, and just
  ;; change the symbol =)
  (context "/groups" +compojure-api-request+
    :coercion    :spec
    :components  [database]
    :tags        ["groups"]

    (GET "/" request
      :swagger {:summary   "Retrieve groups the user owns"
                :produces  #{"application/json"}
                :security  [{:andrewslai-pkce ["roles" "profile"]}]
                :responses {200 {:description "A collection of groups the user owns"
                                 :schema      any?}}}
      (ok (groups-api/get-groups database {:owner-id (oidc/get-user-id (:identity request))})))

    (POST "/" request
      :swagger {:summary   "Create a group"
                :consumes  #{"application/json"}
                :produces  #{"application/json"}
                :security  [{:andrewslai-pkce ["roles" "profile"]}]
                ;;:request   :andrewslai.article/article
                :responses {200 {:description "The group that was created"
                                 ;;:schema      :andrewslai.article/article
                                 :schema      any?}
                            401 {:description "Unauthorized"
                                 :schema      ::error-message}}}
      (try

        (let [group (assoc (:body-params request) :owner-id (oidc/get-user-id (:identity request)))]
          (ok (doto (groups-api/create-group! database group)
                log/info)))
        (catch Exception e
          (log/error "Caught exception " e))))
    (context "/:group-id" [group-id]
      (PUT "/" request
        :swagger {:summary   "Create a group"
                  :consumes  #{"application/json"}
                  :produces  #{"application/json"}
                  :security  [{:andrewslai-pkce ["roles" "profile"]}]
                  ;;:request   :andrewslai.article/article
                  :responses {200 {:description "The group that was created"
                                   ;;:schema      :andrewslai.article/article
                                   :schema      any?}
                              401 {:description "Unauthorized"
                                   :schema      ::error-message}}}
        (try
          (log/info "Creating group!" group-id (:body-params request))
          (let [group (assoc (:body-params request)
                             :id       group-id
                             :owner-id (oidc/get-user-id (:identity request)))]
            (ok (doto (groups-api/create-group! database group)
                  log/info)))
          (catch Exception e
            (log/error "Caught exception " e)))
        )
      (DELETE "/" []
        :swagger {:summary   "Delete a group"
                  :produces  #{"application/json"}
                  :security  [{:andrewslai-pkce ["roles" "profile"]}]
                  ;;:request   :andrewslai.article/article
                  :responses {200 {:description ""
                                   ;;:schema      :andrewslai.article/article
                                   :schema      any?}
                              401 {:description "Unauthorized"
                                   :schema      ::error-message}}}
        (try
          (log/info "Attempting to delete group!" group-id)
          ;; TODO next
          ;; IF user ID = owner id, then allow delete
          (let [group (assoc (:body-params request)
                             :id       group-id
                             :owner-id (oidc/get-user-id (:identity request)))]
            (ok (doto (groups-api/delete-group! database group)
                  log/info)))
          (catch Exception e
            (log/error "Caught exception " e)))

        )
      )
    ))
