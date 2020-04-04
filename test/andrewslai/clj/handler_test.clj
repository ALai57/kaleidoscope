(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.auth.crypto :as encryption]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.core :refer :all]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.utils :refer [parse-response-body]]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]))

(extend-protocol Persistence
  clojure.lang.IAtom
  (get-all-articles [a]
    (:articles (deref a)))
  (get-article-metadata [a article-name]
    (first (filter #(= article-name (:article_url %))
                   (:metadata (deref a)))))
  (get-article-content [a article-id]
    (first (filter #(= article-id (:article_id %))
                   (:content (deref a)))))
  (get-full-article [a article-name]
    (postgres/get-full-article a article-name))
  (get-resume-info [a]
    (:resume-info (deref a))))

(def test-db
  (atom {:articles [{:title "Test article",
                     :article_tags "thoughts",
                     :timestamp "2019-11-07T00:48:08.136082000-00:00",
                     :author "Andrew Lai", :article_url "test-article",
                     :article_id 10}]
         :metadata [{:title "Test article",
                     :article_tags "thoughts",
                     :timestamp #inst "2019-11-07T00:48:08.136082000-00:00",
                     :author "Andrew Lai",
                     :article_url "test-article",
                     :article_id 10}]
         :content [{:article_id 10,
                    :content
                    "<div>\n  <font color=\"#ce181e\"><font face=\"Ubuntu Mono\"><font size=\"5\"><b>A\n          basic test</b></font></font></font><p />\n  <p style=\"color:red\">Many of the</p>\n  <p>Usually, that database</p>\n</div>",
                    :dynamicjs []}]
         :resume-info {:skills [{:id 1,
                                 :name "Periscope Data",
                                 :url "",
                                 :image_url "images/periscope-logo.svg",
                                 :description "",
                                 :skill_category "Analytics Tool"}]
                       :projects []
                       :organizations [{:id 1,
                                        :name "HELIX",
                                        :url "https://helix.northwestern.edu",
                                        :image_url "images/nu-helix-logo.svg",
                                        :description "Science Outreach Magazine"}]}}))

(def test-app (h/app {:db test-db}))


(deftest ping-test
  (testing "Ping works properly"
    (let [response (-> (mock/request :get "/ping")
                       test-app)]
      (is (= 200 (:status response)))
      (is (= #{:sha :service-status} (-> response
                                         parse-response-body
                                         keys
                                         set))))))

(deftest home-test
  (testing "Index page works properly"
    (is (= 200 (-> (mock/request :get "/")
                   test-app
                   :status)))))

(deftest get-all-articles-test
  (testing "get-all-articles endpoint returns all articles"
    (let [response (->> "/articles"
                        (mock/request :get)
                        test-app)]
      (is (= 200 (:status response)))
      (is (= (:articles @test-db)
             (parse-response-body response))))))

(deftest get-full-article-test
  (testing "get-article endpoint returns an article data structure"
    (let [response (->> "/articles/test-article"
                        (mock/request :get)
                        test-app)]
      (is (= 200 (:status response)))
      (is (= #{:article
               :article-name}
             (set (keys (parse-response-body response))))))))

(deftest get-resume-info-test
  (testing "get-resume-info endpoint returns an resume-info data structure"
    (let [response (->> "/get-resume-info"
                        (mock/request :get)
                        test-app)]
      (is (= 200 (:status response)))
      (is (= #{:organizations, :projects, :skills}
             (set (keys (parse-response-body response))))))))

(extend-protocol users/UserPersistence
  clojure.lang.IAtom
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

(def test-users-app (h/app {:user test-user-db}))

(deftest login-test
  (testing "login happy path"
    (let [credentials (json/generate-string {:username "Andrew"
                                             :password "Lai"})

          response (test-users-app (mock/request :post
                                                 "/login"
                                                 credentials))]
      (is (= 200 (:status response)))
      (is (= true (:logged-in? (parse-response-body response))))))
  (testing "login incorrect"
    (let [credentials (json/generate-string {:username "Andrew"
                                             :password "Laia"})
          response (test-users-app (mock/request :post
                                                 "/login"
                                                 credentials))]
      (is (= 200 (:status response)))
      (is (= false (:logged-in? (parse-response-body response)))))))

