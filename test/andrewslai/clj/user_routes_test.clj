(ns andrewslai.clj.user-routes-test
  (:require [andrewslai.clj.auth.crypto :as encryption]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.test-utils :refer [defdbtest]]
            [andrewslai.clj.utils :refer [parse-response-body
                                          body->map
                                          file->bytes]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication
                                           wrap-authorization]]
            [cheshire.core :as json]
            [clojure.data.codec.base64 :as b64]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [deftest is testing]]
            [compojure.api.sweet :refer [api GET POST]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :as mem]
            [ring.mock.request :as mock]
            [andrewslai.clj.persistence.postgres :as postgres]))


(def session-atom (atom {}))

(defn components []
  {:user (-> ptest/db-spec
             postgres/->Postgres
             users/->UserDatabase)
   :session {:store (mem/memory-store session-atom)}})

(defn test-users-app []
  (h/wrap-middleware h/bare-app (components)))

(defn identity-handler []
  (h/wrap-middleware (api
                       (GET "/echo" request
                         {:user-authentication (:user request)})) (components)))

(def b64-encoded-avatar (->> "Hello world!"
                             (map (comp byte int))
                             byte-array
                             b64/encode
                             String.))

(def new-user {:username "new-user"
               :avatar b64-encoded-avatar
               :password "new-password"
               :first_name "new"
               :last_name "user"
               :email "newuser@andrewslai.com"})


(defdbtest user-registration-test ptest/db-spec
  (testing "Registration hapy path"
    (let [{:keys [status headers] :as response}
          ((test-users-app) (mock/request :post "/users"
                                          (json/generate-string new-user)))
          user-url (get headers "Location")]
      (is (= 201 status))
      (is (= "/users/new-user" user-url))
      (is (= #{:id :username :avatar :first_name :last_name :email :role_id}
             (-> response
                 parse-response-body
                 keys
                 set)))
      (testing "Can retrieve the new user"
        (let [{:keys [status headers] :as response}
              ((test-users-app) (mock/request :get user-url))]
          (is (= 200 status))
          (is (= (-> new-user
                     (dissoc :password)
                     (assoc :role_id 2))
                 (parse-response-body response))))))))

(defdbtest login-test ptest/db-spec
  ((test-users-app) (mock/request :post "/users" (json/generate-string new-user)))
  (let [credentials
        (json/generate-string (select-keys new-user [:username :password]))

        {:keys [status headers] :as initial-response}
        ((test-users-app) (mock/request :post "/login" credentials))

        cookie (first (get headers "Set-Cookie"))]
    (testing "login happy path"
      (is (= 200 status))
      (is (contains? headers "Set-Cookie")))
    (testing "cookies work properly"
      (let [{:keys [user-authentication]}
            ((identity-handler) (assoc-in (mock/request :get "/echo")
                                          [:headers "cookie"] cookie))]
        (is (= (dissoc new-user :avatar :password)
               (-> {}
                   (into user-authentication)
                   (dissoc :id :role_id :avatar))))))
    (testing "Can hit admin route with valid session token"
      (let [{:keys [status body]}
            ((test-users-app) (assoc-in (mock/request :get "/admin/")
                                        [:headers "cookie"] cookie))]
        (is (= 200 status))
        (is (= {:message "Got to the admin-route!"} (body->map body)))))
    (testing "Rejected from admin route when valid session token not present"
      (let [{:keys [status body]}
            ((test-users-app) (mock/request :get "/admin/"))]
        (is (= 401 status))
        (is (= "Not authorized" body))))
    (testing "After logout, cannot hit admin routes"
      ((test-users-app) (assoc-in (mock/request :post "/logout")
                                  [:headers "cookie"] cookie))
      (let [{:keys [status body]}
            ((test-users-app) (assoc-in (mock/request :get "/admin/")
                                        [:headers "cookie"] cookie))]
        (is (= 401 status))
        (is (= "Not authorized" body)))))
  (testing "Login with incorrect password"
    (let [credentials (json/generate-string {:username "Andrew", :password "L"})
          {:keys [status headers]}
          ((test-users-app) (mock/request :post "/login" credentials))]
      (is (= 200 status))
      (is (not (contains? headers "Set-Cookie"))))))

(defdbtest user-avatar-test ptest/db-spec
  ((test-users-app) (mock/request :post "/users" (json/generate-string new-user)))
  (testing "Get user avatar"
    (let [{:keys [status body headers]}
          ((test-users-app) (mock/request :get
                                          "/users/new-user/avatar"
                                          ))]
      (is (= 200 status))
      (is (= java.io.BufferedInputStream (type body))))))


(defdbtest update-user-test ptest/db-spec
  (let [{:keys [headers] :as response}
        ((test-users-app) (mock/request :post "/users"
                                        (json/generate-string new-user)))
        user-url (get headers "Location")]
    (testing "Can update the new user"
      (let [user-update {:first_name "new.2"
                         :last_name "user.2"}

            {:keys [status headers] :as response}
            ((test-users-app) (mock/request :patch user-url
                                            (json/generate-string user-update)))]
        (is (= 200 status))
        (is (= user-update (-> response
                               parse-response-body
                               (dissoc :id))))))
    (testing "Can retrieve the new user"
      (let [{:keys [status headers] :as response}
            ((test-users-app) (mock/request :get user-url))]
        (is (= 200 status))
        (is (= {:username "new-user"
                :avatar b64-encoded-avatar
                :first_name "new.2"
                :last_name "user.2"
                :email "newuser@andrewslai.com"
                :role_id 2}
               (parse-response-body response)))))))
