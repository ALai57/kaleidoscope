(ns andrewslai.clj.http-api.groups
  (:require [andrewslai.clj.api.groups :as groups-api]
            [andrewslai.clj.api.authentication :as oidc]
            [andrewslai.cljc.specs.articles] ;; Install specs
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.spec.alpha :as s]
            [compojure.api.meta :as compojure-meta]
            [compojure.api.sweet :refer [context GET POST PUT DELETE]]
            [ring.util.http-response :refer [not-found ok conflict no-content unauthorized]]
            [taoensso.timbre :as log])
  (:import java.util.UUID))

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
      (ok (groups-api/get-users-groups database (oidc/get-user-id (:identity request)))))

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
        (log/info "Creating group!" (:body-params request))
        (let [group (assoc (:body-params request)
                           :owner-id (oidc/get-user-id (:identity request)))]
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
            (log/error "Caught exception " e))))
      (DELETE "/" request
        :swagger {:summary   "Delete a group"
                  :produces  #{"application/json"}
                  :security  [{:andrewslai-pkce ["roles" "profile"]}]
                  ;;:request   :andrewslai.article/article
                  :responses {204 {:description "Success deleting the group"
                                   ;;:schema      :andrewslai.article/article
                                   :schema      any?}
                              401 {:description "Unauthorized"
                                   :schema      ::error-message}}}
        (try
          (let [requester-id (oidc/get-user-id (:identity request))]
            (log/infof "User %s attempting to delete group %s!" requester-id group-id)
            (if-let [result (groups-api/delete-group! database requester-id group-id)]
              (no-content)
              (unauthorized)))
          (catch Exception e
            (log/error "Caught exception " e))))

      (context "/members" []
        (POST "/" request
          :swagger {:summary   "Add a member"
                    :consumes  #{"application/json"}
                    :produces  #{"application/json"}
                    :security  [{:andrewslai-pkce ["roles" "profile"]}]
                    ;;:request   :andrewslai.article/article
                    :responses {200 {:description "The member that was added"
                                     ;;:schema      :andrewslai.article/article
                                     :schema      any?}
                                401 {:description "Unauthorized"
                                     :schema      ::error-message}}}
          (try
            (log/info "Adding member!" (:body-params request))
            (let [requester-id (oidc/get-user-id (:identity request))
                  member       (:body-params request)]
              (ok (doto (groups-api/add-users-to-group! database requester-id group-id member)
                    log/info)))
            (catch Exception e
              (log/error "Caught exception " e)))
          )))
    ))
