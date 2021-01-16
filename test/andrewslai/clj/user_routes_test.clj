(ns andrewslai.clj.user-routes-test
  (:require [andrewslai.clj.embedded-postgres :refer [with-embedded-postgres]]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.postgres2 :as postgres2]
            [andrewslai.clj.routes.users :as user-routes]
            [andrewslai.clj.test-utils :as tu :refer [defdbtest]]
            [andrewslai.clj.utils :refer [parse-body]]
            [cheshire.core :as json]
            [clojure.data.codec.base64 :as b64]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [compojure.api.sweet :refer [api GET]]
            [matcher-combinators.test]
            [ring.middleware.session.memory :as mem]
            [ring.mock.request :as mock]))

(def session-atom (atom {}))

(defn components []
  {:database (postgres2/->Database ptest/db-spec)
   :session {:store (mem/memory-store session-atom)}})

(defn test-users-app []
  (h/wrap-middleware h/app-routes (components)))

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
               :password "CactusGnarlObsidianTheft"
               :first_name "new"
               :last_name "user"
               :email "newuser@andrewslai.com"})


(defn create-user!
  [components user]
  (tu/http-request :post "/users"
                   components {:body-params user}))

(defn get-user
  [components user]
  (tu/http-request :get (str "/users/" user) components))

(defn delete-user!
  [components user credentials]
  (tu/http-request :delete (str "/users/" user)
                   components {:body-params credentials}))

(deftest user-registration-happy-path
  (with-embedded-postgres database
    (let [session     (atom {})
          components  {:database database}]
      (is (match? {:status 201
                   :body #(s/valid? ::user-routes/created_user %)
                   :headers {"Location" (str "/users/" (:username new-user))}}
                  (create-user! components new-user)))

      (is (match? {:status 200
                   :body   (dissoc new-user :password)}
                  (update (get-user components (:username new-user))
                          :body
                          #(dissoc % :id :role_id)))))))

(defn unique-constraint-violation?
  [s]
  (clojure.string/includes? s "ERROR: duplicate key value violates unique constraint"))

(deftest duplicate-user-registration
  (with-embedded-postgres database
    (let [components  {:database database}]
      (create-user! components new-user)
      (is (match? {:status 400
                   :body {:type "PersistenceException"
                          :message unique-constraint-violation?}}
                  (create-user! components new-user))))))

(deftest delete-user
  (with-embedded-postgres database
    (let [components  {:database database}
          credentials (select-keys new-user [:username :password])]
      (create-user! components new-user)
      (is (match? {:status 204 :body map?}
                  (delete-user! components (:username new-user) credentials)))
      (is (match? {:status 404 :body {:message string?}}
                  (get-user components (:username new-user)))))))

(defn insufficient-strength?
  [s]
  (clojure.string/includes? s "failed: (fn sufficient-strength?"))

(deftest registration-invalid-arguments
  (with-embedded-postgres database
    (let [components  {:database database}
          user-path   (str "/users/" (:username new-user))]
      (is (match? {:status 400
                   :body {:type "IllegalArgumentException"
                          :subtype "andrewslai.user/password"
                          :message insufficient-strength?}}
                  (create-user! components
                                (assoc new-user :password "password")))))))

(defdbtest login-test ptest/db-spec
  ((test-users-app) (assoc (mock/request :post "/users" (json/generate-string new-user))
                           :content-type "application/json"))
  (let [credentials (select-keys new-user [:username :password])

        {:keys [status headers] :as initial-response}
        ((test-users-app) (-> (mock/request :post "/sessions/login")
                              (assoc :body-params credentials)))

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
      (let [response
            ((test-users-app) (assoc-in (mock/request :get "/admin/")
                                        [:headers "cookie"] cookie))]
        (is (= 200 (:status response)))
        (is (= {:message "Got to the admin-route!"} (parse-body response)))))
    (testing "Rejected from admin route when valid session token not present"
      (let [{:keys [status body]}
            ((test-users-app) (mock/request :get "/admin/"))]
        (is (= 401 status))
        #_(is (= "Not authorized" body))))
    (testing "After logout, cannot hit admin routes"
      ((test-users-app) (assoc-in (mock/request :post "/sessions/logout")
                                  [:headers "cookie"] cookie))
      (let [{:keys [status body]}
            ((test-users-app) (assoc-in (mock/request :get "/admin/")
                                        [:headers "cookie"] cookie))]
        (is (= 401 status))
        #_(is (= "Not authorized" body)))))
  (testing "Login with incorrect password"
    (let [credentials (json/generate-string {:username "Andrew", :password "L"})
          {:keys [status headers]}
          ((test-users-app) (mock/request :post "/sessions/login" credentials))]
      (is (= 401 status))
      (is (not (contains? headers "Set-Cookie"))))))

(defdbtest user-avatar-test ptest/db-spec
  ((test-users-app) (assoc (mock/request :post "/users" (json/generate-string new-user))
                           :content-type "application/json"))
  (testing "Get user avatar"
    (let [{:keys [status body headers]}
          ((test-users-app) (mock/request :get
                                          "/users/new-user/avatar"
                                          ))]
      (is (= 200 status))
      (is (= java.io.BufferedInputStream (type body))))))

(defdbtest update-user-test ptest/db-spec
  (let [{:keys [headers] :as response}
        ((test-users-app) (assoc (mock/request :post "/users"
                                               (json/generate-string new-user))
                                 :content-type "application/json"))
        user-url (get headers "Location")]
    (testing "Can update the new user"
      (let [user-update {:first_name "new.2"
                         :last_name "user.2"}

            {:keys [status headers] :as response}
            ((test-users-app) (-> (mock/request :patch user-url)
                                  (assoc :body-params user-update)))
            response-body (parse-body response)]
        (is (= 200 status))
        (is (= user-update (select-keys response-body (keys user-update))))
        (is (= (format "users/%s/avatar" (:username new-user))
               (:avatar_url response-body)))))
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
               (-> response
                   parse-body
                   (dissoc :id))))))
    (testing "Error when user update is invalid"
      (let [user-update {:first_name ""}

            {:keys [status headers] :as response}
            ((test-users-app) (mock/request :patch user-url
                                            (json/generate-string user-update)))]
        (is (= 400 status))
        (is (= {:type "IllegalArgumentException"
                :subtype "andrewslai.user/user-update"}
               (select-keys (-> response
                                parse-body)
                            [:type :subtype])))))))
