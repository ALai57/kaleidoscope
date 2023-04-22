(ns kaleidoscope.http-api.articles
  (:require [kaleidoscope.api.articles :as articles-api]
            [kaleidoscope.api.authentication :as oidc]
            [kaleidoscope.models.articles] ;; Install specs
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [compojure.api.meta :as compojure-meta]
            [compojure.api.sweet :refer [context GET POST PUT]]
            [ring.util.http-response :refer [not-found ok conflict]]
            [taoensso.timbre :as log]))

(s/def ::message string?)
(s/def ::error-message (s/keys :req-un [::message]))

(defn remove-port
  [hostname]
  (first (string/split hostname #":")))

(comment
  (string/split "localhost:5000" #":")
  (string/split "localhost" #":")
  )

(defn get-host
  [request]
  (-> request
      (get-in [:headers "host"])
      (remove-port)))

(defn ->article [article-url {:keys [body-params] :as request}]
  (-> body-params
      (select-keys [:article-name])
      (assoc :article-url article-url
             :hostname    (get-host request)
             :author      (oidc/get-full-name (:identity request)))))

(defn ->commit [{:keys [body-params] :as request}]
  (select-keys body-params [:branch-id :content :created-at :modified-at]))

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
    :coercion    :spec
    :components  [database]
    :tags        ["articles"]

    (GET "/" request
      :swagger {:summary   "Retrieve all articles"
                :produces  #{"application/json"}
                :security  [{:andrewslai-pkce ["roles" "profile"]}]
                :responses {200 {:description "A collection of all articles"
                                 :schema      :kaleidoscope.article/articles}}}
      (ok (articles-api/get-articles database {:hostname (get-host request)})))

    (context "/:article-url" [article-url]
      (GET "/" request
        :swagger {:summary    "Retrieve a single article"
                  :produces   #{"application/json"}
                  :security   [{:andrewslai-pkce ["roles" "profile"]}]
                  :parameters {:path {:article-url :kaleidoscope.article/article-url}}
                  :responses  {200 {:description "A single article"
                                    :schema      :kaleidoscope.article/article}}}
        (if-let [article (articles-api/get-articles database {:article-url article-url})]
          (ok article)
          (not-found {:reason "Missing"})))

      (context "/branches" _
        :tags ["branches"]

        (GET "/" []
          :swagger {:summary  "Retrieve all branches for a specific article"
                    :security [{:andrewslai-pkce ["roles" "profile"]}]
                    :produces #{"application/json"}}
          (ok (articles-api/get-branches database {:article-url article-url})))

        (context "/:branch-name" [branch-name]
          (PUT "/" request
            :swagger {:summary   "Create an article branch"
                      :consumes  #{"application/json"}
                      :produces  #{"application/json"}
                      :security  [{:andrewslai-pkce ["roles" "profile"]}]
                      :request   :kaleidoscope.article/article
                      :responses {200 {:description "The article branch that was created"
                                       :schema      :kaleidoscope.article/article}
                                  401 {:description "Unauthorized"
                                       :schema      ::error-message}}}
            (try
              (let [article (->article article-url request)
                    branch  {:branch-name branch-name}]
                (ok (doto (articles-api/create-branch! database (merge article branch))
                      log/info)))
              (catch Exception e
                (log/error "Caught exception " e))))

          ;; Should probably be POST `publish-date`
          (PUT "/publish" request
            :swagger {:summary   "Publish an article branch"
                      :produces  #{"application/json"}
                      :security  [{:andrewslai-pkce ["roles" "profile"]}]
                      :responses {200 {:description "The article branch that was created"
                                       :schema      :kaleidoscope.article/article}
                                  401 {:description "Unauthorized"
                                       :schema      ::error-message}}}
            (try
              (log/infof "Publishing article %s and branch %s" article-url branch-name)
              (let [[{:keys [branch-id]}] (articles-api/get-branches database {:branch-name branch-name
                                                                               :article-url article-url})
                    result                (articles-api/publish-branch! database branch-id)]
                (ok result))
              (catch Exception e
                (log/error "Caught exception " e))))

          (context "/versions" []
            :tags ["versions"]
            (POST "/" request
              :swagger {:summary   "Create a new version (commit) on a branch"
                        :consumes  #{"application/json"}
                        :produces  #{"application/json"}
                        :security  [{:andrewslai-pkce ["roles" "profile"]}]
                        :request   :kaleidoscope.article/article
                        :responses {200 {:description "The version that was created"
                                         :schema      :kaleidoscope.article/article}
                                    401 {:description "Unauthorized"
                                         :schema      ::error-message}}}
              (try
                (let [commit (->commit request)
                      result (articles-api/new-version! database
                                                        {:branch-name   branch-name
                                                         :hostname      (get-host request)
                                                         :article-url   article-url
                                                         :article-tags  (get-in request [:params :article-tags] "thoughts")
                                                         :article-title (get-in request [:params :article-title])
                                                         :author        (oidc/get-full-name (:identity request))}
                                                        commit)]
                  (log/info result)
                  (ok result))
                (catch Exception e
                  (log/error "Caught exception " e)
                  (conflict (ex-message e))))))
          )))))

(def branches-routes
  ;; HACK: I think the `context` macro may be broken, because it emits an s-exp
  ;; with let-bindings out of order: +compojure-api-request+ is referred to
  ;; before it is bound. This means that, to fix the bug, you'd need to either
  ;; reverse the order of the emitted bindings, or do what I did, and just
  ;; change the symbol =)
  (context "/branches" +compojure-api-request+
    :coercion   :spec
    :components [database]
    :tags       ["branches"]

    (GET "/" request
      :swagger {:summary  "Retrieve all branches"
                :security [{:andrewslai-pkce ["roles" "profile"]}]
                :produces #{"application/json"}}
      (let [query-params (select-keys (cske/transform-keys csk/->kebab-case-keyword (:query-params request))
                                      [:article-id :article-url])
            branches     (articles-api/get-branches database (assoc query-params :hostname (get-host request)))]
        (if (empty? branches)
          (not-found {:reason "Missing"})
          (ok branches))))

    (POST "/" request
      :swagger {:summary   "Create a branch"
                :consumes  #{"application/json"}
                :produces  #{"application/json"}
                :security  [{:andrewslai-pkce ["roles" "profile"]}]
                :request   :kaleidoscope.article/article-branch
                :responses {200 {:description "The branch that was created"
                                 :schema      :kaleidoscope.article/article-branch}
                            401 {:description "Unauthorized"
                                 :schema      ::error-message}}}
      (try
        (ok (articles-api/create-branch! database (assoc (:body-params request)
                                                         :hostname (get-host request)
                                                         :author   (oidc/get-full-name (:identity request)))))
        (catch Exception e
          (log/error "Caught exception " e))))

    (context "/:branch-id" [branch-id]
      (GET "/versions" request
        :tags ["versions"]
        :swagger {:summary   "Get versions"
                  :produces  #{"application/json"}
                  :security  [{:andrewslai-pkce ["roles" "profile"]}]
                  :responses {200 {:description "The version that was created"
                                   :schema      :kaleidoscope.article/article-branch}
                              401 {:description "Unauthorized"
                                   :schema      ::error-message}}}
        (let [branches (articles-api/get-versions database {:branch-id (Integer/parseInt branch-id)})]
          (if (empty? branches)
            (not-found {:reason "Missing"})
            (ok (reverse (sort-by :created-at branches)))))))))

(def compositions-routes
  ;; HACK: I think the `context` macro may be broken, because it emits an s-exp
  ;; with let-bindings out of order: +compojure-api-request+ is referred to
  ;; before it is bound. This means that, to fix the bug, you'd need to either
  ;; reverse the order of the emitted bindings, or do what I did, and just
  ;; change the symbol =)
  (context "/compositions" +compojure-api-request+
    :coercion :spec
    :components [database]
    :tags ["compositions"]

    (GET "/" request
      :swagger {:summary     "Retrieve all published articles"
                :description (str "This endpoint retrieves all published articles. "
                                  "The endpoint is currently not paginated")
                :produces    #{"application/json"}
                :responses   {200 {:description "A collection of all published articles"
                                   :schema      :kaleidoscope.article/articles}}}
      (log/infof "Getting compositions for host `%s`" (get-host request))
      (ok (articles-api/get-published-articles database {:hostname (get-host request)})))

    (GET "/:article-url" [article-url :as request]
      :swagger {:summary    "Retrieve a single published article"
                :produces   #{"application/json"}
                :parameters {:path {:article-url :kaleidoscope.article/article-url}}
                :responses  {200 {:description "A single published article"
                                  :schema      :kaleidoscope.article/article}}}
      (let [result (articles-api/get-published-articles database {:article-url article-url})]
        (if (empty? result)
          (not-found {:reason "Missing"})
          (ok (first result)))))))