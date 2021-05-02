(ns andrewslai.cljs.events.articles
  (:require [ajax.core :as ajax]
            [andrewslai.cljc.specs.articles]
            [cljs.spec.alpha :as s]
            [day8.re-frame.http-fx]
            [re-frame.core :refer [dispatch reg-event-db reg-event-fx]]))

(defn load-article [db [_ response]]
  (assoc db
         :loading? false
         :active-content response))
(reg-event-db :load-article load-article)

(defn make-article-url [article-name]
  (str "/articles/" (name article-name)))

(reg-event-fx
 :request-article
 (fn [{:keys [db]} [_ article-name]]
   {:http-xhrio {:method          :get
                 :uri             (make-article-url article-name)
                 :format          (ajax/json-response-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:load-article]
                 :on-failure      [:load-article]}
    :db         (assoc db :loading? true)}))

(defn load-recent-articles [db [_ response]]
  (assoc db
         :loading? false
         :recent-content (filter (partial s/valid? :andrewslai.article/article)
                                 response)))
(reg-event-db :load-recent-articles load-recent-articles)

(reg-event-fx
 :request-recent-articles
 (fn [{:keys [db]} [_]]
   {:http-xhrio {:method          :get
                 :uri             "/articles"
                 :format          (ajax/json-response-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:load-recent-articles]
                 :on-failure      [:load-recent-articles]}
    :db         (assoc db :loading? true)}))
