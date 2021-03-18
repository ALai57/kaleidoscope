(ns andrewslai.cljs.events.editor
  (:require [cljsjs.slate-html-serializer :as shs]
            [ajax.core :as ajax]
            [re-frame.core :refer [dispatch reg-event-db reg-event-fx]]
            [andrewslai.cljs.modals.editor :refer [create-article-failure-modal
                                                   create-article-success-modal]]
            [andrewslai.cljs.modal :refer [modal-template close-modal]]
            [reagent.core :as reagent]))

;; HTML Serializer
;; This serializer is used for the following purposes: * serialize slatejs
;; document to HTML string for display and persistence in app-db. * deserialize
;; HTML string to slatejs document to import existing html into editor.
(def html-serializer-rules
  [{:deserialize
    (fn deserialize-all
      [el next]
      (let [el-name (.toLowerCase (.-tagName el))
            child-nodes (.-childNodes el)
            deserialized-child-nodes (next child-nodes)]
        (case el-name
          "em"
          (clj->js {:object "mark"
                    :type "italic"
                    :nodes deserialized-child-nodes})
          "p"
          (clj->js {:object "block"
                    :type "paragraph"
                    :nodes deserialized-child-nodes})
          "strong"
          (clj->js {:object "mark"
                    :type "bold"
                    :nodes deserialized-child-nodes})

          "code"
          (clj->js {:object "block"
                    :type "code"
                    :nodes deserialized-child-nodes})

          js/undefined)))

    :serialize
    (fn serialize-all
      [object children]
      (let [slate-object (.-object object)
            slate-type (.-type object)]
        ;; NOTE: The serializers below need to be kept in sync with render-node
        ;; and render-mark functions!
        (case [slate-object slate-type]
          ["block"  "paragraph"] (reagent/as-element [:p {} children])
          ["mark" "italic"] (reagent/as-element [:em {} children])
          ["mark" "bold"] (reagent/as-element [:strong {} children])
          ["block" "code"] (reagent/as-element [:pre {} [:code children]])
          ["block" "code-inline"] (reagent/as-element [:pre {} [:code children]])
          ["block" "code-block"] (reagent/as-element [:pre {:style {:white-space "pre-wrap"
                                                                    :background "hsl(30,80%,90%)"}}
                                                      [:code children]])
          js/undefined)))}])

(def html-serializer
  (let [Html (-> js/SlateHtmlSerializer
                 (js->clj :keywordize-keys true)
                 :default)]
    (-> {:rules html-serializer-rules}
        clj->js
        Html.)))

(comment
  (->> {:document {:nodes [{:object "block"
                            :type "paragraph"
                            :nodes [{:object "text"
                                     :leaves [{:text "Hello world!"}]}]}]}}
       clj->js
       (.fromJSON js/Slate.Value))

  (.serialize html-serializer
              (->> {:document {:nodes [{:object "block"
                                        :type "paragraph"
                                        :nodes [{:object "text"
                                                 :leaves [{:text "Hello world!"}]}]}]}}
                   clj->js
                   (.fromJSON js/Slate.Value)))
  )

(defn editor-model->clj [model]
  (let [html (.serialize html-serializer model)]
    {:html html}))

(defn editor-text-changed [db [_ new-value]]
  (let [serialized-text (editor-model->clj new-value)]
    (assoc db :editor-data new-value)))
(reg-event-db :editor-text-changed
              editor-text-changed)

(defn editor-metadata-changed [db [_ new-value]]
  (assoc db :editor-metadata new-value))
(reg-event-db :editor-metadata-changed
              editor-metadata-changed)

(reg-event-db
 :save-success
 (fn [db [_ response]]
   (js/console.log "POST RESPONSE" response)
   (dispatch [:show-modal (create-article-success-modal response)])
   db))

(reg-event-db
 :save-failure
 (fn [db [_ response]]
   (js/console.log "POST RESPONSE" response)
   (dispatch [:show-modal (create-article-failure-modal response)])
   db))

(defn title->url
  [title]
  (-> title
      str
      clojure.string/lower-case
      (clojure.string/replace  #"[!|.|(|)|]" "")
      (clojure.string/replace  " " "-")))

(reg-event-fx
 :save-article!
 (fn [{:keys [db]} [_ {:keys [title] :as article}]]
   {:http-xhrio {:method          :put
                 :uri             (str "/articles/" (title->url title))
                 :params          article
                 :headers         {:Authorization (str "Bearer " (.-token (:keycloak db)))
                                   :Content-Type "application/json"}
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:save-success]
                 :on-failure      [:save-failure]}
    :db         (assoc db :loading? true)}))
