(ns kaleidoscope.clients.error-reporter)

(defprotocol ErrorReporter
  (report! [this e] "Report an error"))
