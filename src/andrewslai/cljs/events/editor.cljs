(ns andrewslai.cljs.events.editor
  (:require [cljsjs.slate-html-serializer :as shs]
            [re-frame.core :refer [dispatch reg-event-db]]
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

(reg-event-db
  :editor-text-changed
  (fn [db [_ new-value]]
    (let [serialized-text (editor-model->clj new-value)]
      #_(println serialized-text)
      (assoc db :editor-data new-value))))
