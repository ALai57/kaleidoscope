(ns andrewslai.clj.routes.users
  (:require [andrewslai.clj.api.users :as users-api]
            [andrewslai.clj.entities.user :as user]
            [andrewslai.clj.routes.admin :refer [access-error]]
            [andrewslai.clj.utils :refer [file->bytes parse-body]]
            [buddy.auth.accessrules :refer [restrict]]
            [cheshire.core :as json]
            [clojure.data.codec.base64 :as b64]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [compojure.api.sweet :refer [context defroutes DELETE GET PATCH POST]]
            [compojure.api.meta :as compojure-meta]
            [ring.util.http-response :refer [bad-request created not-found ok no-content]]
            [ring.util.response :as response]
            [slingshot.slingshot :refer [try+]]
            [spec-tools.core :as st-core]
            [spec-tools.swagger.core :as st]
            [taoensso.timbre :as log]
            [compojure.api.middleware :as mw]))

(s/def ::avatar string?)
(s/def ::avatar_url string?)

(s/def ::user (s/keys :req-un [::avatar
                               :andrewslai.user/first_name
                               :andrewslai.user/last_name
                               :andrewslai.user/username
                               :andrewslai.user/email
                               :andrewslai.user/password]))

(s/def ::created_user (s/keys :req-un [::avatar
                                       :andrewslai.user/first_name
                                       :andrewslai.user/last_name
                                       :andrewslai.user/username
                                       :andrewslai.user/email
                                       ::avatar_url]))

(s/def ::user-update (s/keys :opt-un [::avatar
                                      :andrewslai.user/first_name
                                      :andrewslai.user/last_name]))

(s/def :andrewslai.credentials/credentials
  (s/keys :req-un [:andrewslai.user/password
                   :andrewslai.user/username]))

(defn decode-avatar [{:keys [avatar] :as m}]
  (if avatar
    (assoc m :avatar (b64/decode (.getBytes avatar)))
    m))

(defn wrap-user [handler]
  (fn [{user-id :identity
        {database :database} ::mw/components
        :as req}]
    (if (and user-id database)
      (handler (assoc req
                      :user (user/get-user-profile-by-id database user-id)))
      (handler (assoc req :user nil)))))

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

(defroutes users-routes
  (context "/users" []
    :tags ["users"]
    :coercion :spec
    :components [database]

    (GET "/:username" [username]
      :swagger {:summary "Retrieve a user's profile"
                :produces #{"application/json"}
                :responses {200 {:description "User profile"
                                 :schema ::user}
                            404 {:description "Not found"
                                 :schema string?}}}
      (let [result (users-api/get-user database username)]
        (if result
          (ok result)
          (not-found))))

    (DELETE "/:username" {credentials :body-params}
      :swagger {:request :andrewslai.credentials/credentials}
      (let [result (users-api/delete-user! database credentials)]
        (if (map? result)
          (no-content)
          (not-found))))

    (PATCH "/:username" {{:keys [username]} :route-params
                         update-map :body-params
                         :as request}
      :swagger {:summary "Update a user"
                :consumes #{"application/json"}
                :produces #{"application/json"}
                :request  ::user-update
                :responses {200 {:description "The updated fields"
                                 :schema ::user-update}}}
      (try+
       (let [result (users-api/update-user! database
                                            username
                                            (decode-avatar update-map))]
         (-> result
             (assoc :avatar_url (format "users/%s/avatar" username))
             ok))
       (catch [:type :IllegalArgumentException] e
         (-> (bad-request)
             (assoc :body e)))))

    (POST "/" request
      :swagger {:summary   "Create a user"
                :consumes  #{"application/json"}
                :produces  #{"application/json"}
                :request   ::user
                :responses {200 {:description "The user that was created"
                                 :schema      ::created_user}}}
      (try+
       (let [{:keys [username password] :as payload} (:body-params request)
             result (users-api/register-user! database
                                              (-> payload
                                                  (dissoc :password)
                                                  decode-avatar)
                                              password)]
         (log/info "User created:" result)
         (-> (created)
             (assoc :headers {"Location" (str "/users/" username)})
             (assoc :body result)
             (assoc-in [:body :avatar_url] (format "users/%s/avatar" username))))
       (catch [:type :PersistenceException] e
         (log/info (pr-str e))
         (-> (bad-request)
             (assoc :body e)))
       (catch [:type :IllegalArgumentException] e
         (log/info (pr-str e))
         (-> (bad-request)
             (assoc :body e)))))

    (GET "/:username/avatar" [username]
      :swagger {:summary "Get a user's avatar"
                :produces #{"image/png"}
                :responses {200 {:description "User's avatar"
                                 :schema bytes?}
                            404 {:description "Not found"
                                 :schema string?}}}
      (let [{:keys [avatar]}
            (user/get-user-profile database username)]
        (if avatar
          (-> (response/response (io/input-stream avatar))
              (response/content-type "image/png")
              (response/header "Cache-control" "max-age=0, must-revalidate"))
          (not-found (format "Cannot find user: %s" username)))))))
