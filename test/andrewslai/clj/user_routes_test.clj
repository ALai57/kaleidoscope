(ns andrewslai.clj.user-routes-test
  (:require [andrewslai.clj.embedded-postgres :refer [with-embedded-postgres]]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.postgres2 :as postgres2]
            [andrewslai.clj.routes.users :as user-routes]
            [andrewslai.clj.test-utils :as tu]
            [clojure.data.codec.base64 :as b64]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [compojure.api.sweet :refer [api GET]]
            [matcher-combinators.test]
            [ring.middleware.session.memory :as mem]))

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

(defn login-user
  [components credentials]
  (tu/http-request :post "/sessions/login"
                   components {:body-params credentials}))

(defn get-user-avatar
  ([components user]
   (get-user-avatar components user {}))
  ([components user options]
   (tu/http-request :get (str "/users/" user "/avatar") components options)))

(defn update-user!
  [components user user-update]
  (tu/http-request :patch (str "/users/" user)
                   components {:body-params user-update}))

(defn admin-route
  [components options]
  (tu/http-request :get "/admin/" components options))

(defn logout
  [components options]
  (tu/http-request :post "/sessions/logout" components options))

(defn unique-constraint-violation?
  [s]
  (clojure.string/includes? s "ERROR: duplicate key value violates unique constraint"))

(defn insufficient-strength?
  [s]
  (clojure.string/includes? s "failed: (fn sufficient-strength?"))

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

(deftest cannot-register-duplicate-user
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

(deftest login-test
  (with-embedded-postgres database
    (let [session     (atom {})
          components  {:database database
                       :session  {:store (mem/memory-store session)}}
          user-path   (str "/users/" (:username new-user))
          _           (create-user! components new-user)
          response    (login-user components (select-keys new-user [:username :password]))
          [cookie]    (get-in response [:headers "Set-Cookie"])]

      (testing "Sucessful login returns `Set-Cookie` header"
        (is (match? {:status 200
                     :headers {"Set-Cookie" coll?}}
                    response)))

      (testing "Can get to admin route by supplying cookie"
        (is (match? {:status 200 :body {:message "Got to the admin-route!"}}
                    (admin-route components {:headers {"cookie" cookie}}))))

      (testing "Can't get to admin route if no cookie present"
        (is (match? {:status 401 :body {:reason "Not authorized"}}
                    (admin-route components {}))))

      (testing "After logout, cannot hit admin routes"
        (logout components {:headers {"cookie" cookie}})
        (is (match? {:status 401 :body {:reason "Not authorized"}}
                    (admin-route components {:headers {"cookie" cookie}})))))))

(deftest incorrect-password-cannot-login
  (with-embedded-postgres database
    (let [components  {:database database}
          user-path   (str "/users/" (:username new-user))
          _           (create-user! components new-user)
          response    (login-user components {:username "Andrew", :password "L"})]
      (is (match? {:status 401} response))
      (is (not (contains? (:headers response) "Set-Cookie"))))))

(deftest user-avatar-test
  (with-embedded-postgres database
    (let [components  {:database database}]
      (create-user! components new-user)
      (is (match? {:status 200
                   :body   "Hello world!"}
                  (get-user-avatar components (:username new-user)
                                   {:parser identity}))))))

(deftest update-user-test
  (with-embedded-postgres database
    (let [components  {:database database}
          user-update {:first_name "new.2"
                       :last_name "user.2"}]
      (create-user! components new-user)

      (testing "Can make a user update"
        (is (match? {:status 200
                     :body (assoc user-update
                                  :avatar_url (format "users/%s/avatar" (:username new-user)))}
                    (update-user! components (:username new-user) user-update))))

      (testing "Can retrieve the new user"
        (is (match? {:status 200
                     :body   (-> new-user
                                 (dissoc :password)
                                 (assoc :first_name "new.2"
                                        :last_name "user.2"))}
                    (get-user components (:username new-user))))))))

(deftest update-user-failure-test
  (with-embedded-postgres database
    (let [components  {:database database}
          user-update {:first_name "new.2"
                       :last_name "user.2"}]
      (create-user! components new-user)

      (testing "Error when user update is invalid"
        (is (match? {:status 400
                     :body {:type "IllegalArgumentException"
                            :subtype "andrewslai.user/user-update"}}
                    (update-user! components (:username new-user)
                                  {:first_name ""})))))))
