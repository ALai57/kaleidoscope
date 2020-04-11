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
            [clojure.test :refer [deftest is testing]]
            [compojure.api.sweet :refer [api GET POST]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :as mem]
            [ring.mock.request :as mock]))

(extend-protocol users/UserPersistence
  clojure.lang.IAtom
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

(def test-user-db
  (atom {:users [{:id 1
                  :username "Andrew"}]
         :logins [{:id 1
                   :hashed_password (encryption/encrypt (encryption/make-encryption)
                                                        "Lai")}]}))

(def session-atom (atom {}))
(def components {:user test-user-db
                 :session-options {:store (mem/memory-store session-atom)}})
(def test-users-app (h/wrap-middleware h/bare-app components))


(def identity-handler
  (h/wrap-middleware (api
                       (GET "/echo" request
                         {:user-authentication (:user request)})) components))

(defn extract-ring-session [cookie]
  (let [ring-session-regex #"^.*ring-session=(?<ringsession>[a-z0-9-]*);.*$"
        matcher (re-matcher ring-session-regex cookie)]
    (.matches matcher)
    (str (.group matcher "ringsession"))))

(deftest login-test
  (let [credentials (json/generate-string {:username "Andrew"
                                           :password "Lai"})

        {:keys [status headers]} (test-users-app (mock/request :post
                                                               "/login"
                                                               credentials))
        cookie (first (get headers "Set-Cookie"))]
    (testing "login happy path"
      (is (= 302 status))
      (is (contains? headers "Set-Cookie")))
    (testing "cookies work properly"
      (let [{:keys [user-authentication]}
            (identity-handler (assoc-in (mock/request :get
                                                      "/echo")
                                        [:headers "cookie"] cookie))]
        (is (= (first (:users @test-user-db))
               (into {} user-authentication)))))
    (testing "Can hit admin route with valid session token"
      (let [{:keys [status body]}
            (test-users-app (assoc-in (mock/request :get
                                                    "/admin/")
                                      [:headers "cookie"] cookie))]
        (is (= 200 status))
        (is (= {:message "Got to the admin-route!"} (body->map body)))))
    (testing "Rejected from admin route when valid session token not present"
      (let [{:keys [status body]}
            (test-users-app (mock/request :get "/admin/"))]
        (is (= 401 status))
        (is (= "Not authorized" body)))))
  (testing "login with incorrect password"
    (let [credentials (json/generate-string {:username "Andrew"
                                             :password "Laia"})
          {:keys [status headers]} (test-users-app (mock/request :post
                                                                 "/login"
                                                                 credentials))]
      (is (= 302 status))
      (is (not (contains? headers "Set-Cookie"))))))

(deftest user-avatar-test
  (testing "Get user avatar"
    (let [{:keys [status body headers]}
          (test-users-app (mock/request :get
                                        "/users/1/avatar"
                                        ))]
      (is (= 200 status))
      (is (= java.io.BufferedInputStream (type body))))))


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
                                   }}))
