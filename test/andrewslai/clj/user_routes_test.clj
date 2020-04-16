(ns andrewslai.clj.user-routes-test
  (:require [andrewslai.clj.auth.crypto :as encryption]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.utils :refer [parse-response-body
                                          body->map]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication
                                           wrap-authorization]]
            [cheshire.core :as json]
            [clojure.data.codec.base64 :as b64]
            [clojure.test :refer [deftest is testing]]
            [compojure.api.sweet :refer [api GET POST]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :as mem]
            [ring.mock.request :as mock]))

(extend-protocol users/UserPersistence
  clojure.lang.IAtom
  (create-user! [a user]
    (swap! a update :users conj (users/create-user-payload user))
    user)
  (create-login! [a id password]
    (swap! a update :logins conj {:id id, :password password}))
  (register-user! [a user]
    (users/-register-user-impl! a user))
  (update-user [a username update-map]
    (let [idx (first (keep-indexed #(when (= username (:username %2)) %1) (:users @a)))]
      (swap! a update-in [:users idx] merge update-map)
      (get-in @a [:users idx])))
  (get-user-by-id [a user-id]
    (first (filter #(= user-id (:id %))
                   (:users (deref a)))))
  (get-user [a username]
    (first (filter #(= username (:username %))
                   (:users (deref a)))))
  (get-password [a user-id]
    (:hashed_password (first (filter #(= user-id (:id %))
                                     (:logins (deref a))))))
  (login [a credentials]
    (users/-login a credentials)))


(defn file->bytes [file]
  (with-open [xin (clojure.java.io/input-stream file)
              xout (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy xin xout)
    (.toByteArray xout)))

(def test-user-db
  (atom {:users [{:id 1
                  :username "Andrew"
                  :avatar (file->bytes (clojure.java.io/resource "avatars/happy_emoji.jpg"))}]
         :logins [{:id 1
                   :hashed_password (encryption/encrypt (encryption/make-encryption)
                                                        "Lai")}]}))

(comment
  (users/register-user! test-user-db
                        {:first_name "Andrew"
                         :last_name "Lai"
                         :email "me@andrewslai.com"
                         :username "Andrew"
                         :avatar (byte-array (map (comp byte int) "Hello world!"))
                         :password "password"})

  @test-user-db
  (users/get-user test-user-db "new-user")
  )

(comment
  (require '[clojure.data.codec.base64 :as b64])

  (with-open [in (clojure.java.io/input-stream (clojure.java.io/resource "avatars/happy_emoji.jpg"))
              out (java.io.ByteArrayOutputStream.)
              #_(clojure.java.io/output-stream "output.b64")]
    (b64/encoding-transfer in out))

  (= (String. (file->bytes (clojure.java.io/resource "avatars/happy_emoji.jpg")))
     (String. (b64/decode (file->bytes (clojure.java.io/file "dev-resources/encoded-png.b64")))))

  (slurp  (b64/decode (b64/encode (.getBytes "Hello"))))
  (slurp (b64/decode (.getBytes (slurp (b64/encode (.getBytes "Hello"))))))
  (= (String. (b64/encode (file->bytes (clojure.java.io/resource "avatars/happy_emoji.jpg"))))
     (String. (file->bytes (clojure.java.io/file "dev-resources/encoded-png.b64"))))

  )

(def session-atom (atom {}))
(def components {:user test-user-db
                 :session-options {:store (mem/memory-store session-atom)}})
(def test-users-app (h/wrap-middleware h/bare-app components))


(def identity-handler
  (h/wrap-middleware (api
                       (GET "/echo" request
                         {:user-authentication (:user request)})) components))


(deftest login-test
  (let [credentials (json/generate-string {:username "Andrew", :password "Lai"})

        {:keys [status headers] :as initial-response}
        (test-users-app (mock/request :post "/login" credentials))

        cookie (first (get headers "Set-Cookie"))]
    (testing "login happy path"
      (is (= 200 status))
      (is (contains? headers "Set-Cookie")))
    (testing "cookies work properly"
      (let [{:keys [user-authentication]}
            (identity-handler (assoc-in (mock/request :get "/echo")
                                        [:headers "cookie"] cookie))]
        (is (= (first (:users @test-user-db))
               (into {} user-authentication)))))
    (testing "Can hit admin route with valid session token"
      (let [{:keys [status body]}
            (test-users-app (assoc-in (mock/request :get "/admin/")
                                      [:headers "cookie"] cookie))]
        (is (= 200 status))
        (is (= {:message "Got to the admin-route!"} (body->map body)))))
    (testing "Rejected from admin route when valid session token not present"
      (let [{:keys [status body]}
            (test-users-app (mock/request :get "/admin/"))]
        (is (= 401 status))
        (is (= "Not authorized" body))))
    (testing "After logout, cannot hit admin routes"
      (test-users-app (assoc-in (mock/request :post "/logout")
                                [:headers "cookie"] cookie))
      (let [{:keys [status body]}
            (test-users-app (assoc-in (mock/request :get "/admin/")
                                      [:headers "cookie"] cookie))]
        (is (= 401 status))
        (is (= "Not authorized" body)))))
  (testing "Login with incorrect password"
    (let [credentials (json/generate-string {:username "Andrew", :password "L"})
          {:keys [status headers]}
          (test-users-app (mock/request :post "/login" credentials))]
      (is (= 200 status))
      (is (not (contains? headers "Set-Cookie"))))))

(deftest user-avatar-test
  (testing "Get user avatar"
    (let [{:keys [status body headers]}
          (test-users-app (mock/request :get
                                        "/users/Andrew/avatar"
                                        ))]
      (is (= 200 status))
      (is (= java.io.BufferedInputStream (type body))))))

(deftest user-registration-test
  (let [b64-encoded-avatar (->> "Hello world!"
                                (map (comp byte int))
                                byte-array
                                b64/encode
                                String.)]
    (testing "Registration hapy path"
      (let [{:keys [status headers] :as response}
            (test-users-app (mock/request :post "/users"
                                          (json/generate-string
                                            {:username "new-user"
                                             :avatar b64-encoded-avatar
                                             :password "new-password"
                                             :first_name "new"
                                             :last_name "user"
                                             :email "newuser@andrewslai.com"})))
            user-url (get headers "Location")]
        (is (= 201 status))
        (is (= "/users/new-user" user-url))
        (is (= #{:id :username} (-> response
                                    parse-response-body
                                    keys
                                    set)))
        (testing "Can retrieve the new user"
          (let [{:keys [status headers] :as response}
                (test-users-app (mock/request :get user-url))]
            (is (= 200 status))
            (is (= {:username "new-user"
                    :avatar b64-encoded-avatar
                    :first_name "new"
                    :last_name "user"
                    :email "newuser@andrewslai.com"
                    :role_id 2}
                   (parse-response-body response)))))))))

(deftest update-user-test
  (let [b64-encoded-avatar (->> "Hello world!"
                                (map (comp byte int))
                                byte-array
                                b64/encode
                                String.)]
    (testing "Registration hapy path"
      (let [{:keys [headers] :as response}
            (test-users-app (mock/request :post "/users"
                                          (json/generate-string
                                            {:username "new-user"
                                             :avatar b64-encoded-avatar
                                             :password "new-password"
                                             :first_name "new"
                                             :last_name "user"
                                             :email "newuser@andrewslai.com"})))
            user-url (get headers "Location")]
        (testing "Can update the new user"
          (let [{:keys [status headers] :as response}
                (test-users-app (mock/request :patch user-url
                                              (json/generate-string
                                                {:username "new-user"
                                                 :first_name "new.2"
                                                 :last_name "user.2"})))]
            (is (= 200 status))
            (is (= {:username "new-user"
                    :avatar b64-encoded-avatar
                    :first_name "new.2"
                    :last_name "user.2"
                    :email "newuser@andrewslai.com"
                    :role_id 2}
                   (-> response
                       parse-response-body
                       (dissoc :id))))))
        (testing "Can retrieve the new user"
          (let [{:keys [status headers] :as response}
                (test-users-app (mock/request :get user-url))]
            (is (= 200 status))
            (is (= {:username "new-user"
                    :avatar b64-encoded-avatar
                    :first_name "new.2"
                    :last_name "user.2"
                    :email "newuser@andrewslai.com"
                    :role_id 2}
                   (parse-response-body response)))))))))

(comment
  (require '[ring.middleware.cookies :as cook])
  (cook/cookies-request {:protocol "HTTP/1.1",
                         :server-port 80,
                         :server-name "localhost",
                         :remote-addr "127.0.0.1",
                         :uri "/logout/",
                         :scheme :http,
                         :request-method :post,
                         :headers {"host" "localhost"
                                   "cookie" "ring-session=16d02d7d-8522-4548-8b2e-f209ec153194;Path=/;HttpOnly",
                                   }})
  (clojure.pprint/pprint (json/generate-string
                           {:username "new-user"
                            :password "new-password"
                            :first_name "new"
                            :last_name "user"
                            :email "newuser@andrewslai.com"}))

  (b64/decode (.getBytes (slurp (clojure.java.io/file "dev-resources/encoded-image.png"))))
  )
