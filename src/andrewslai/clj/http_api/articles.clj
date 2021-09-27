(ns andrewslai.clj.http-api.articles
  (:require [andrewslai.clj.api.articles :as articles-api]
            [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.http-api.admin :as admin]
            [buddy.auth.accessrules :refer [restrict]]
            [clojure.spec.alpha :as s]
            [compojure.api.meta :as compojure-meta]
            [compojure.api.sweet :refer [context GET PUT]]
            [ring.util.http-response :refer [not-found ok]]
            [taoensso.timbre :as log]))

;; RENAME THIS TO ROUTES.BLOG

(s/def ::message string?)
(s/def ::error-message (s/keys :req-un [::message]))

(defn ->article [article-url {:keys [body-params] :as request}]
  (-> body-params
      (select-keys [:title :article_tags :article_name :content])
      (assoc :article_url article-url)
      (assoc :author (auth/get-full-name (:identity request)))))

(defn create-article-handler
  [database article-url request]
  (try
    (let [article (->article article-url request)]
      (ok (doto (articles-api/create-article! database article)
            log/info)))
    (catch Exception e
      (log/info "Caught exception " e))))

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

(def articles-routes
  ;; HACK: I think the `context` macro may be broken, because it emits an s-exp
  ;; with let-bindings out of order: +compojure-api-request+ is referred to
  ;; before it is bound. This means that, to fix the bug, you'd need to either
  ;; reverse the order of the emitted bindings, or do what I did, and just
  ;; change the symbol =)
  (context "/articles" +compojure-api-request+
    :coercion :spec
    :components [database]
    :tags ["articles"]

    (GET "/" []
      :swagger {:summary "Retrieve all articles"
                :description (str "This endpoint retrieves all articles. "
                                  "The endpoint is currently not paginated")
                :produces #{"application/json"}
                :responses {200 {:description "A collection of all articles"
                                 :schema :andrewslai.article/articles}}}
      (ok (articles-api/get-all-articles database)))

    (GET "/:article-name" [article-name]
      :swagger {:summary "Retrieve a single article"
                :produces #{"application/json"}
                :parameters {:path {:article-name :andrewslai.article/article_name}}
                :responses {200 {:description "A single article"
                                 :schema :andrewslai.article/article}}}
      (if-let [article (articles-api/get-article database article-name)]
        (ok article)
        (not-found {:reason "Missing"})))

    (PUT "/:article-url" [article-url]
      :swagger {:summary "Create an article"
                :consumes #{"application/json"}
                :produces #{"application/json"}
                :request :andrewslai.article/article
                :responses {200 {:description "The article that was created"
                                 :schema :andrewslai.article/article}
                            401 {:description "Unauthorized"
                                 :schema ::error-message}}}
      (restrict (partial create-article-handler database article-url)
                {:handler admin/is-authenticated?
                 :on-error admin/access-error}))))
