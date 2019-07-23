(ns andrewslai.clj.mock)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Create mock data
;; Mostly used for prototyping changes to UI
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def mock-data
  [{:name "Bank of America",
    :error_institution 0.01,
    :error_plaid 0,
    :success 0.99,
    :last_status_change "2019-05-14T00:00:48Z",
    :status "HEALTHY"}
   {:name "Chase",
    :error_institution 0,
    :error_plaid 0,
    :success 1,
    :last_status_change "2019-05-14T00:00:48Z",
    :status "CRITICAL"}])
