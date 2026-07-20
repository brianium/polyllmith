(ns brianium.polyllmith.post-process
  "deps-new `:post-process-fn` for the polyllmith template.

  The template keeps harness-agnostic skills in `.agents/skills/` and lets
  Claude Code discover them through a symlink under `.claude/skills/` (see the
  \"Agent Skills\" section in the generated `AGENTS.md`). deps-new copies files
  by dereferencing symlinks, so it can't reproduce that layout on its own — a
  committed symlink would be slurped as a regular file. We finish the layout
  here: after generation, create `.claude/skills/clojure-eval` as a relative
  symlink onto `../../.agents/skills/clojure-eval`.

  This namespace sits next to `template.edn` (on the classpath, but not in any
  `:transform`), so it runs during generation and is never copied into the
  generated project."
  (:require [clojure.java.io :as io])
  (:import (java.io File)
           (java.nio.file Files LinkOption Paths)
           (java.nio.file.attribute FileAttribute)))

(defn- delete-recursively!
  "Remove `f` (a file, directory, or symlink). Symlinks are removed without
  following them, so the shared `.agents/` copy is never touched."
  [^File f]
  (when (Files/exists (.toPath f) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
    (when (and (.isDirectory f)
               (not (Files/isSymbolicLink (.toPath f))))
      (doseq [child (.listFiles f)]
        (delete-recursively! child)))
    (io/delete-file f true)))

(defn- copy-dir!
  "Recursive copy fallback for platforms where symlink creation is denied
  (e.g. bare Windows). Leaves a real, if duplicated, skill Claude can find."
  [^File src ^File dst]
  (.mkdirs dst)
  (doseq [^File f (.listFiles src)]
    (let [target (io/file dst (.getName f))]
      (if (.isDirectory f)
        (copy-dir! f target)
        (io/copy f target)))))

(defn link-shared-skills
  "Point `.claude/skills/clojure-eval` at the shared `.agents/` copy so Claude
  Code discovers the skill while a single canonical copy lives under
  `.agents/skills/`. Falls back to copying if symlinks are unsupported."
  [_edn {:keys [target-dir]}]
  (let [skills-dir (io/file target-dir ".claude" "skills")
        link       (io/file skills-dir "clojure-eval")
        shared     (io/file target-dir ".agents" "skills" "clojure-eval")]
    (when (.exists shared)
      (.mkdirs skills-dir)
      (delete-recursively! link) ; idempotent under :overwrite
      (try
        (Files/createSymbolicLink
         (.toPath link)
         (Paths/get "../../.agents/skills/clojure-eval" (make-array String 0))
         (make-array FileAttribute 0))
        (println "  Linked .claude/skills/clojure-eval -> ../../.agents/skills/clojure-eval")
        (catch Exception e
          (println "  Symlink unsupported"
                   (str "(" (ex-message e) ");")
                   "copied .agents/skills/clojure-eval into .claude/skills/ instead.")
          (copy-dir! shared link))))))
