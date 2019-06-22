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



;; Dispatched when setting the active panel
(reg-event-db
 :set-active-panel
 (fn [db [_ value]]
   (-> db
       (assoc :active-panel value)
       (assoc :active-content nil))))

(reg-event-db
 :retrieve-content
 (fn [db [_ content-type content-name]]

   (println "Retrieve-content path:"
            (str "/get-content/" (name content-type) "/" (name content-name)))

   (GET
       (str "/get-content/" (name content-type) "/" (name content-name))
       {:handler #(dispatch [:process-response %1])
        :error-handler #(dispatch [:bad-response %1])})

   (-> db
       (assoc :loading? true)
       (assoc :active-panel content-type)
       (assoc :active-content nil))))

(reg-event-db
 :process-response
 (fn
   [db [_ response]]
   (println "SUCCESS Retrieved content: " response)
   (-> db
       (assoc :loading? false) ;; take away that "Loading ..." UI
       (assoc :active-content response))))

(reg-event-db
 :bad-response
 (fn
   [db [_ response]]
   (-> db
       (assoc :loading? false)
       (assoc :active-content "Unable to load content"))))
