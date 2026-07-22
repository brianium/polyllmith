;;; Directory Local Variables            -*- no-byte-compile: t -*-
;;; For more information see (info "(emacs) Directory Variables")

;; `bb.edn' at the repo root makes CIDER auto-detect babashka as the build
;; tool; force the JVM Clojure CLI and always include the :dev alias so all
;; Polylith bricks land on the classpath.
((nil . ((cider-preferred-build-tool . clojure-cli)
         (cider-clojure-cli-aliases . ":dev"))))
