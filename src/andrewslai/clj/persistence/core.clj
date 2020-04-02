(ns andrewslai.clj.persistence.core)

(defprotocol Persistence
  (save-article! [_])
  (get-full-article [_ article-name])
  (get-all-articles [_])
  (get-resume-info [_])
  (create-user! [_ user])
  (get-password [_ user-id]))
