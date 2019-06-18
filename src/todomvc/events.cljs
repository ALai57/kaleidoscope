(ns todomvc.events
  (:require
   [todomvc.db    :refer [default-db todos->local-store]]
   [re-frame.core :refer [reg-event-db
                          reg-event-fx
                          inject-cofx
                          path
                          after
                          dispatch]]
   [cljs.spec.alpha :as s]
   [ajax.core :refer [GET]]))


;; -- Interceptors --------------------------------------------------------------
;;
;; There is a tutorial on Interceptors in re-frame's `/docs`, but to get
;; you going fast, here's a very high level description ...
;;
;; Each interceptor wraps around the "handler", so that its `:before`
;; is called before the event handler runs, and its `:after` runs after
;; the event handler has run.
;;
;; Interceptors with a `:before` action, can be used to "inject" values
;; into what will become the `coeffects` parameter of an event handler.
;; That's a way of giving an event handler access to certain resources,
;; like values in LocalStore.
;;
;; Interceptors with an `:after` action, can, among other things,
;; process the effects produced by the event handler. One could
;; check if the new value for `app-db` correctly matches a Spec.
;;


;; -- First Interceptor ------------------------------------------------------
;;
;; When included in the interceptor chain, this interceptor runs
;; `check-and-throw` `after` the event handler has finished (schema)
;;
;; If the event handler corrupted the value for `app-db` an exception will be
;; thrown. All state is held in `app-db`:: we are effectively validating the
;; ENTIRE state of the application after each event handler runs.


(defn check-and-throw
  "Throws an exception if `db` doesn't match the Spec `a-spec`."
  [a-spec db]
  (when-not (s/valid? a-spec db)
    (throw (ex-info (str "spec check failed: " (s/explain-str a-spec db)) {}))))

;; now we create an interceptor using `after`
(def check-spec-interceptor (after (partial check-and-throw :todomvc.db/db)))


;; -- Second Interceptor -----------------------------------------------------
;;
;; This interceptor runs `after` an event handler, and it stores the
;; current todos into local storage.

(def ->local-store (after todos->local-store))


;; -- Interceptor Chain ------------------------------------------------------
;;
;; Create interceptor chain shared by all handlers that manipulate todos.

(def todo-interceptors
  [check-spec-interceptor ;; ensure spec is  valid  (after)
   (path :todos)          ;; 1st param given to handler is this path within db
   ->local-store])        ;; write todos to localstore  (after)


;; -- Helpers -----------------------------------------------------------------

(defn allocate-next-id
  "Returns the next todo id.
  Assumes todos are sorted.
  Returns one more than the current largest id."
  [todos]
  ((fnil inc 0) (last (keys todos))))


;; -- Event Handlers ----------------------------------------------------------

;; Establishes initial application state in `app-db`.
;; Merges:
;;   1. Any todos stored in LocalStore (from the last session of this app)
;;   2. Default initial values
;;
;; Inject todos in LocalStore into first coeffect parameter via interceptor
;;    `(inject-cofx :local-store-todos)`
;;
;; Look at the bottom of `db.cljs` for the `:local-store-todos` cofx
;; registration.


;; Dispatched when initializing DB
(reg-event-fx
 :initialise-db

 ;; Interceptors
 [(inject-cofx :local-store-todos) ;; Add data from localstorage
  check-spec-interceptor]          ;; (after) check app-db for Spec

 ;; Handler
 (fn [{:keys [db local-store-todos]} _]
   {:db (assoc default-db :todos local-store-todos)}))



;; Dispatched when user clicks filter buttons on bottom of display
(reg-event-db
 :set-showing
 [check-spec-interceptor]
 (fn [db [_ new-filter-kw]] ;; new-filter-kw :: #{:all, :active, :done}
   (assoc db :showing new-filter-kw)))

;; Rewrite of the event handler (above) using a `path` Interceptor
;; `path` operates a little like `update-in`
;;
;; A `path` interceptor has BOTH a before and after action.
;; Path = path into `app-db`, like: [:a :b 1]
;; "before" =  replace the app-db with the value
;; of `app-db` at the nominated path.
;; "after" = take event handler returned value and place it back into
;; app-db at the nominated path.

#_(reg-event-db
   :set-showing

   ;; this now a chain of 2 interceptors. Note use of `path`
   [check-spec-interceptor (path :showing)]

   ;; The event handler
   ;; Because of the `path` interceptor above, the 1st parameter to
   ;; the handler below won't be the entire 'db', and instead will
   ;; be the value at the path `[:showing]` within db.
   ;; Equally the value returned will be the new value for that path
   ;; within app-db.
   (fn [old-showing-value [_ new-showing-value]]
     new-showing-value))                  ;; return new state for the path



(reg-event-db
 :add-todo
 todo-interceptors

 ;; The "path" interceptor in `todo-interceptors` means 1st parameter is the
 ;; value at `:todos` path within `db`, rather than the full `db`.
 (fn [todos [_ text]]
   (let [id (allocate-next-id todos)]
     (assoc todos id {:id id :title text :done false}))))


(reg-event-db
  :toggle-done
  todo-interceptors
  (fn [todos [_ id]]
    (update-in todos [id :done] not)))


(reg-event-db
  :save
  todo-interceptors
  (fn [todos [_ id title]]
    (assoc-in todos [id :title] title)))


(reg-event-db
  :delete-todo
  todo-interceptors
  (fn [todos [_ id]]
    (dissoc todos id)))


(reg-event-db
 :clear-completed
 todo-interceptors
 (fn [todos _]
   (let [done-ids (->> (vals todos)   ;; which todos have a :done of true
                       (filter :done)
                       (map :id))]
     (reduce dissoc todos done-ids))))      ;; delete todos which are done


(reg-event-db
 :complete-all-toggle
 todo-interceptors
 (fn [todos _]
   (let [new-done (not-every? :done (vals todos))]
     (reduce #(assoc-in %1 [%2 :done] new-done)
             todos
             (keys todos)))))


;; Dispatched when setting the active panel
(reg-event-db
 :set-active-panel
 (fn [db [_ value]]
   (assoc db :active-panel value)))

(reg-event-db
 :retrieve-content
 (fn [db [_ content-type content-name]]

   (println "Retrieve-content path:"
            (str "/get-content/" (name content-type) "/" (name content-name)))

   (GET
       (str "/get-content/" (name content-type) "/" (name content-name))
       {:handler #(dispatch [:process-response %1])
        :error-handler #(dispatch [:bad-response %1])})

   (assoc db :loading? true)
   (assoc db :active-content :loading)
   #_(assoc db :active-content content-name)))

(reg-event-db
 :process-response
 (fn
   [db [_ response]]
   (println "Retrieved content: " response)
   (-> db
       (assoc :loading? false) ;; take away that "Loading ..." UI
       (assoc :active-content response))))

(reg-event-db
 :bad-response
 (fn
   [db [_ response]]
   (-> db
       (assoc :loading? false) ;; take away that "Loading ..." UI
       (assoc :active-content "Unable to load content"))))
