(ns kaleidoscope.http-api.audiences
  (:require [compojure.api.sweet :refer [context GET PUT DELETE]]
            [kaleidoscope.api.audiences :as audiences-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [ring.util.http-response :refer [ok not-found]]))

(def audiences-routes
  ;; HACK: I think the `context` macro may be broken, because it emits an s-exp
  ;; with let-bindings out of order: +compojure-api-request+ is referred to
  ;; before it is bound. This means that, to fix the bug, you'd need to either
  ;; reverse the order of the emitted bindings, or do what I did, and just
  ;; change the symbol =)
  (context "/article-audiences" +compojure-api-request+
    :coercion    :spec
    :components  [database]
    :tags        ["audiences"]

    (GET "/" request
      :swagger {:summary   "Retrieve all audiences"
                :produces  #{"application/json"}
                :security  [{:andrewslai-pkce ["roles" "profile"]}]
                :responses {200 {:description "A collection of all audiences"
                                 :schema      :kaleidoscope.article/articles}}}
      (let [audiences (->> {:hostname (hu/get-host request)}
                           (audiences-api/get-article-audiences database))]
        (if (empty? audiences)
          (not-found)
          (ok audiences))))

    (PUT "/" request
      :swagger {:summary   "Create a new audience"
                :produces  #{"application/json"}
                :security  [{:andrewslai-pkce ["roles" "profile"]}]
                :responses {200 {:description "A collection of all audiences"
                                 :schema      :kaleidoscope.article/articles}}}
      (let [{:keys [article-id group-id]} (:params request)]
        (ok (audiences-api/add-audience-to-article! database
                                                    {:id       article-id
                                                     :hostname (hu/get-host request)}
                                                    {:id group-id}))))
    (DELETE "/:id" [id]
      :swagger {:summary   "Delete an audience"
                :produces  #{"application/json"}
                :security  [{:andrewslai-pkce ["roles" "profile"]}]
                :responses {200 {:description "A collection of all audiences"
                                 :schema      :kaleidoscope.article/articles}}}
      (ok (audiences-api/delete-article-audience! database id)))))
