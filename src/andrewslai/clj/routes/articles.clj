(ns andrewslai.clj.routes.articles
  (:require [andrewslai.clj.api.articles :as articles-api]
            [andrewslai.clj.entities.article :as article]
            [andrewslai.clj.routes.admin :as admin]
            [andrewslai.clj.utils :refer [parse-body]]
            [buddy.auth.accessrules :refer [restrict]]
            [compojure.api.sweet :refer [context GET POST]]
            [ring.util.http-response :refer [ok not-found]]
            [clojure.spec.alpha :as s]
            [spec-tools.swagger.core :as swagger]))

(s/def ::cookie string?)
(s/def ::message string?)
(s/def ::error-message (s/keys :req-un [::message]))

(defn create-article-handler [{{database :database} :components
                               article :body-params}]
  (ok (articles-api/create-article! database article)))

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
                                 :schema :andrewslai.article/articles}}}
      (ok (articles-api/get-all-articles (get-in +compojure-api-request+
                                                 [:components :database]))))

    (GET "/:article-name" [article-name]
      :swagger {:summary "Retrieve a single article"
                :produces #{"application/json"}
                :parameters {:path {:article-name :andrewslai.article/article_name}}
                :responses {200 {:description "A single article"
                                 :schema :andrewslai.article/article}}}
      (let [article (articles-api/get-article (get-in +compojure-api-request+
                                                      [:components :database])
                                              article-name)]
        (if article
          (ok article)
          (not-found {:reason "Missing"}))))

    (POST "/" []
      :swagger {:summary "Create an article"
                :consumes #{"application/json"}
                :produces #{"application/json"}
                ::swagger/parameters {:body :andrewslai.article/article
                                      :header-params {:cookie ::cookie}}
                :responses {200 {:description "The article that was created"
                                 :schema :andrewslai.article/article}
                            401 {:description "Unauthorized"
                                 :schema ::error-message}}}
      (restrict create-article-handler
                {:handler admin/is-authenticated?
                 :on-error admin/access-error}))))
