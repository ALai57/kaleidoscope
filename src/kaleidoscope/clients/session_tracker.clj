(ns kaleidoscope.clients.session-tracker)

(defprotocol SessionTracker
  (start! [this] "Start a session"))
