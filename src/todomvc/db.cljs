(ns todomvc.db
  (:require [cljs.reader]
            [cljs.spec.alpha :as s]
            [re-frame.core :as re-frame]))


;; -- Spec --------------------------------------------------------------------
;;
;; The value in app-db should always match this spec. Only event handlers
;; can change the value in app-db so, after each event handler
;; has run, we re-check app-db for correctness (compliance with the Schema).
;;
;; How is this done? Look in events.cljs and you'll notice that all handlers
;; have an "after" interceptor which does the spec re-check.

(s/def ::id int?)
(s/def ::title string?)
(s/def ::done boolean?)
(s/def ::todo (s/keys :req-un [::id ::title ::done]))
(s/def ::todos (s/and (s/map-of ::id ::todo)
                      #(instance? PersistentTreeMap %)))
(s/def ::showing       ;; what todos are shown to the user?
  #{:all               ;; all todos are shown
    :active            ;; only todos whose :done is false
    :done              ;; only todos whose :done is true
    })
(s/def ::db (s/keys :rEeq-un [::todos ::showing ::active-panel]))

;; -- Default app-db Value  ---------------------------------------------------

(def default-db           ;; what gets put into app-db by default.
  {:todos   (sorted-map)  ;; an empty list of todos. Use (int) :id as the key
   :showing :all
   :active-panel :home})        ;; show all todos


;; -- Local Storage  ----------------------------------------------------------

(def ls-key "todos-reframe")                         ;; localstore key

(defn todos->local-store
  "Puts todos into localStorage"
  [todos]
  (.setItem js/localStorage ls-key (str todos)))     ;; EDN map


;; -- cofx Registrations  -----------------------------------------------------

(re-frame/reg-cofx
 :local-store-todos
 (fn [cofx _]
   (assoc cofx :local-store-todos
          (into (sorted-map) ;; localstore -> read todos -> sortedmap
                (some->> (.getItem js/localStorage ls-key)
                         (cljs.reader/read-string)    ;; EDN map -> map
                         )))))
