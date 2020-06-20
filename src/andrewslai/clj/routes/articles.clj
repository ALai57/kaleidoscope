(ns andrewslai.clj.routes.articles
  (:require [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.routes.admin :as admin]
            [andrewslai.clj.utils :refer [parse-body]]
            [buddy.auth.accessrules :refer [restrict]]
            [compojure.api.sweet :refer [context defroutes GET POST]]
            [ring.util.http-response :refer [ok not-found]]
            [clojure.spec.alpha :as s]
            [spec-tools.spec :as spec]))

(s/def ::article_id spec/integer?)
(s/def ::article_name spec/string?)
(s/def ::title spec/string?)
(s/def ::article_tags spec/string?)
(s/def ::timestamp (s/or :date spec/inst? :string spec/string?))
(s/def ::article_url spec/string?)
(s/def ::author spec/string?)
(s/def ::content spec/string?)

(s/def ::article (s/keys :req-un [::title
                                  ::article_tags
                                  ::author
                                  ::timestamp
                                  ::article_url
                                  ::article_id]
                         :opt-un [::content]))

(s/def ::articles (s/coll-of ::article))

(defprotocol ArticlesAdapter
  "A protocol that adapts some kind of incoming request input and translates the
  request into a clojure data structure that the business logic can handle (i.e.
  something that the articles persistence protocol can understand)"
  (get-all-articles [_ request])
  (get-article [_ request article-name])
  (create-article! [_ request]))

(defrecord CompojureArticlesAdapter []
  ArticlesAdapter
  (get-all-articles [_ request]
    (ok (articles/get-all-articles (get-in request [:components :db]))))
  (get-article [_ request article-name]
    (let [article (-> request
                      (get-in [:components :db])
                      (articles/get-article article-name))]
      (if article
        (ok article)
        (not-found))))
  (create-article! [_ request]
    (ok (-> request
            (get-in [:components :db])
            (articles/create-article! (parse-body request))))))

(defroutes articles-routes
  (context "/articles" request
    :tags [:spec]
    :coercion :spec

    (GET "/" []
      :responses {200 {:schema ::articles}}
      (get-all-articles (->CompojureArticlesAdapter) request))

    (GET "/:article-name" [article-name]
      :responses {200 {:schema ::article}}
      (get-article (->CompojureArticlesAdapter) request article-name))

    (restrict
     (POST "/" []
       (create-article! (->CompojureArticlesAdapter) request))
     {:handler admin/is-authenticated?
      :on-error admin/access-error})))
