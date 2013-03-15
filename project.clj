(defproject irc-deploy "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [com.palletops/pallet "0.8.0-beta.4"]
                 [org.cloudhoist/pallet-vmfest "0.3.0-alpha.2"]
                 [org.clojars.tbatchelli/vboxjws "4.2.4"]
                 [ch.qos.logback/logback-classic "1.0.9"]
                 [commons-codec "1.7"]]
  :plugins [[com.palletops/pallet-lein "0.6.0-beta.6"]]
  :repositories {"sonatype" "http://oss.sonatype.org/content/repositories/releases"})
