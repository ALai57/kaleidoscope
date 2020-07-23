(ns andrewslai.clj.routes.articles
  (:require [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.routes.admin :as admin]
            [andrewslai.clj.utils :refer [parse-body]]
            [buddy.auth.accessrules :refer [restrict]]
            [compojure.api.sweet :refer [context GET POST]]
            [ring.util.http-response :refer [ok not-found]]
            [clojure.spec.alpha :as s]
            [spec-tools.swagger.core :as swagger]))


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
        (not-found {:reason "Missing"}))))
  (create-article! [_ request]
    (ok (-> request
            (get-in [:components :db])
            (articles/create-article! (:body-params request))))))

(defn create-article-handler [request]
  (create-article! (->CompojureArticlesAdapter) request))

(s/def ::cookie string?)
(s/def ::message string?)
(s/def ::error-message (s/keys :req-un [::message]))

(def articles-routes
  ;; HACK: I think the `context` macro may be broken, because it emits an s-exp
  ;; with let-bindings out of order: +compojure-api-request+ is referred to
  ;; before it is bound. This means that, to fix the bug, you'd need to either
  ;; reverse the order of the emitted bindings, or do what I did, and just
  ;; change the symbol =)
  (context "/articles" +compojure-api-request+
    :coercion :spec
    :tags ["articles"]

    (GET "/" []
      :swagger {:summary "Retrieve all articles"
                :description (str "This endpoint retrieves all articles. "
                                  "The endpoint is currently not paginated")
                :produces #{"application/json"}
                :responses {200 {:description "A collection of all articles"
                                 :schema ::articles/articles}}}
      (get-all-articles (->CompojureArticlesAdapter) +compojure-api-request+))

    (GET "/:article-name" [article-name]
      :swagger {:summary "Retrieve a single article"
                :produces #{"application/json"}
                :parameters {:path {:article-name ::articles/article_name}}
                :responses {200 {:description "A single article"
                                 :schema ::articles/article}}}
      (get-article (->CompojureArticlesAdapter)
                   +compojure-api-request+
                   article-name))

    (POST "/" []
      :swagger {:summary "Create an article"
                :consumes #{"application/json"}
                :produces #{"application/json"}
                ::swagger/parameters {:body ::articles/article
                                      :header-params {:cookie ::cookie}}
                :responses {200 {:description "The article that was created"
                                 :schema ::articles/article}
                            401 {:description "Unauthorized"
                                 :schema ::error-message}}}
      (restrict create-article-handler
                {:handler admin/is-authenticated?
                 :on-error admin/access-error}))))
