(ns andrewslai.cljs.server-comms.articles
  (:require [ajax.core :refer [DELETE GET PATCH POST]]
            [andrewslai.cljs.modals.users :refer [registration-success-modal
                                                  registration-failure-modal
                                                  delete-success-modal
                                                  delete-failure-modal
                                                  update-success-modal
                                                  update-failure-modal
                                                  login-failure-modal]]
            [re-frame.core :refer [dispatch]]
            [clojure.string :as str]))


(defprotocol ArticleOperations
  (get-article- [_ article-url]))


(defn make-article-url [article-name]
  (str "/articles/" (name article-name)))
(defn make-get-all-articles-url [] "/articles")

(defn get-article [article-name]
  (GET (make-article-url article-name)
      {:handler
       (fn [response]
         (dispatch [:load-article response]))
       :error-handler
       (fn [response]
         (dispatch [:load-article "Unable to load content"]))}))

(defn get-articles [n]
  (GET (make-get-all-articles-url)
      {:handler
       (fn [response]
         (dispatch [:load-recent-articles response]))
       :error-handler
       (fn [response]
         (dispatch [:load-recent-articles "Unable to load content"]))}))
