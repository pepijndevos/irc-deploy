(ns irc-deploy.core
  (:require [pallet.crate.upstart :as upstart]
            [pallet.crate.initd :as initd]
            [clojure.string :as string])
  (:use [pallet.api :only [group-spec node-spec server-spec execute-with-image-credentials-metadata]]
        [pallet.node :only [primary-ip]]
        [pallet.crate :only [defplan target-name target-node]]
        [pallet.crate.service :only [service service-supervisor-config]]
        [pallet.crate.automated-admin-user :only [automated-admin-user]]
        [pallet.actions :only [package package-manager package-source exec-script user group remote-file file directory remote-directory]]
        [pallet.action :only [with-action-options]]
        [pallet.stevedore :only [chain-commands]]
        [pallet.config-file.format :only [name-values sectioned-properties]]
        [pallet.script.lib :only [heredoc]]))

(def default-upstart
  {:start-on "runlevel [2345"
   :stop-on "stop on runlevel [!2345]"
   :respawn true
   :respawn-limit "5 60"})

(defplan restart-job [job]
  ;(service {:service-name job
  ;          :supervisor :upstart}
  ;         {:action :stop})
  (service {:service-name job
            :supervisor :upstart}
           {:action :start}))

(defn parse-ip [ip]
  (reduce +
    (map bit-shift-left
      (map #(Integer/parseInt %) (string/split ip #"\."))
      (range 24 -1 -8))))

(defplan nodejs []
  (package-source "nodejs" :aptitude {:url "ppa:chris-lea/node.js"})
  (package "nodejs"))

(defplan install-kiwi []
  (nodejs)
  (remote-directory
    "/var/lib/kiwi"
    :url "https://github.com/pepijndevos/KiwiIRC/archive/development.tar.gz"
    :owner "kiwi"
    :group "kiwi")
  (exec-script "cd /var/lib/kiwi/"
               "npm install"))

(defplan kiwi-conf []
  (group "kiwi" :action :create)
  (user "kiwi"
        :action :create
        :home "/var/lib/kiwi"
        :create-home true
        :system true
        :group "kiwi")
  (remote-file "/etc/ssl/certs/STAR_teamrelaychat_nl.key" :local-file "resources/STAR_teamrelaychat_nl.key")
  (remote-file "/etc/ssl/certs/STAR_teamrelaychat_nl.crt" :local-file "resources/STAR_teamrelaychat_nl.crt")
  (remote-file "/etc/ssl/certs/PositiveSSLCA2.crt" :local-file "resources/PositiveSSLCA2.crt")
  (remote-file "/etc/ssl/certs/AddTrustExternalCARoot.crt" :local-file "resources/AddTrustExternalCARoot.crt"))

(defplan kiwi-settings []
  (service-supervisor-config
    :upstart
    (assoc default-upstart
           :service-name "kiwiirc"
           :exec "/var/lib/kiwi/kiwi -f"
           :pre-start-exec "/var/lib/kiwi/kiwi build")
    {}))

(defplan kiwi []
  (kiwi-conf)
  (install-kiwi)
  (restart-job "kiwiirc"))

(def kiwi-server
  (server-spec
   :phases {:settings  kiwi-settings
            :configure kiwi}))

(defplan ngircd-conf []
  (remote-file
    "/etc/ngircd/ngircd.conf"
    :owner "irc"
    :group "irc"
    :content (sectioned-properties
               {:global  {:Listen "127.0.0.1,localhost"
                          :Name (target-name)
                          ;Keep this setting in sync with PIDFILE in /etc/init.d/ngircd
                          :PidFile "/var/run/ircd/ngircd.pid"
                          ;Keep this setting in sync with DAEMONUSER in /etc/init.d/ngircd
                          :ServerGID "irc"
                          ;Keep this setting in sync with DAEMONUSER in /etc/init.d/ngircd
                          :ServerUID "irc"
                          ;Not required by server but by RFC!
                          :AdminInfo1 "A private server"
                          :AdminInfo2 "The Internet"
                          :AdminEMail "support@teamrelaychat.nl"}
                :Options {:PAM "no"
                          :SyslogFacility "local1"}
                :Limits  {:MaxConnectionsIP 0}})))

(defplan ngircd []
  (package-source "ngircd" :aptitude {:url "http://debian.barton.de/debian"
                                      :release "lenny"
                                      :key-url "http://debian.barton.de/debian/archive-key.gpg"})
  (package "ngircd")
  (ngircd-conf)
  (service {:service-name "ngircd"
            :supervisor :initd}
           {:action :restart}))

(def ngircd-server
  (server-spec
   :phases {:configure ngircd}))

(defplan new-logging []
  (directory "/var/lib/znc/modules/log/tmpl"
             :owner "znc"
             :group "znc")
  (remote-file "/var/lib/znc/modules/log.cpp"
               :url "https://raw.github.com/pepijndevos/znc/master/modules/log.cpp"
               :owner "znc"
               :group "znc")
  (remote-file "/var/lib/znc/modules/log/tmpl/index.tmpl"
               :url "https://raw.github.com/pepijndevos/znc/master/modules/data/log/tmpl/index.tmpl"
               :owner "znc"
               :group "znc")
  (exec-script "znc-buildmod /var/lib/znc/modules/log.cpp"))

(defplan registration []
  (directory "/var/lib/znc/modules/register/tmpl"
             :owner "znc"
             :group "znc")
  (remote-file "/var/lib/znc/modules/register.cpp"
               :url "https://raw.github.com/pepijndevos/znc/register/modules/register.cpp"
               :owner "znc"
               :group "znc")
  (remote-file "/var/lib/znc/modules/register/tmpl/index.tmpl"
               :url "https://raw.github.com/pepijndevos/znc/register/modules/data/register/tmpl/index.tmpl"
               :owner "znc"
               :group "znc")
  (exec-script "CXXFLAGS=\"-DREGISTER_HOST=localhost\" znc-buildmod /var/lib/znc/modules/register.cpp"))

(defplan znc-conf []
  (group "znc" :action :create)
  (user "znc"
        :action :create
        :create-home true
        :home "/var/lib/znc"
        :system true
        :group "znc")
  (package "libcap2-bin")
  (exec-script "setcap 'cap_net_bind_service=+ep' /usr/bin/znc")
  (directory "/var/lib/znc/configs/"
             :owner "znc"
             :group "znc")
  (exec-script
    (when-not (file-exists? "/var/lib/znc/configs/znc.conf")
      (heredoc "/var/lib/znc/configs/znc.conf"
               ~(slurp "resources/znc.conf")
               {:literal true})))
  (file "/var/lib/znc/configs/znc.conf" :owner "znc" :group "znc")
  (remote-file "/var/lib/znc/znc.pem"
               :local-file "resources/znc.pem"
               :owner "znc"
               :group "znc"))

(defplan znc-settings []
  (service-supervisor-config
    :upstart
    (assoc default-upstart
           :service-name "znc"
           :exec "znc --foreground --datadir=/var/lib/znc"
           :setuid "znc"
           :setgid "znc")
    {}))

(defplan znc []
  (package-source "backports" :aptitude {:url "http://us.archive.ubuntu.com/ubuntu/"
                                         :release "precise-backports"
                                         :scopes ["main" "restricted" "universe" "multiverse"]})
  (package "znc" :enable "precise-backports")
  (package "znc-dev" :enable "precise-backports")
  (znc-conf)
  (new-logging)
  (registration)
  (restart-job "znc"))

(def znc-server
  (server-spec
   :phases {:settings  znc-settings
            :configure znc}))

(defplan hubot-dcc []
  (directory "/var/lib/hubot/data"
             :owner "hubot"
             :group "hubot")
  (remote-file "/var/lib/hubot/scripts/dcc.coffee"
               :url "https://gist.github.com/pepijndevos/5495692/raw/dcc.coffee"
               :owner "hubot"
               :group "hubot"))

(defplan hubot-setenv []
  (directory "/var/lib/hubot/data"
             :owner "hubot"
             :group "hubot")
  (remote-file "/var/lib/hubot/scripts/setenv.coffee"
               :url "https://raw.github.com/github/hubot-scripts/master/src/scripts/setenv.coffee"
               :owner "hubot"
               :group "hubot"))

(defplan install-hubot []
  (group "hubot" :action :create)
  (user "hubot"
        :action :create
        :create-home true
        :home "/var/lib/hubot"
        :system true
        :group "hubot")
  (exec-script (if-not (file-exists? "/var/lib/hubot/bin/hubot")
                 (do
                   "npm install -g coffee-script"
                   "npm install -g hubot"
                   "hubot -c /var/lib/hubot"
                   "cd /var/lib/hubot"
                   "chmod +x /var/lib/hubot/bin/hubot"
                   "npm install hubot-irc --save"
                   "npm install")
                 (do
                   "cd /var/lib/hubot"
                   "npm update"))))


(defplan hubot-conf []
  (remote-file "/var/lib/hubot/hubot-scripts.json"
               :owner "hubot"
               :group "hubot"
               :overwrite-changes true
               :local-file "resources/hubot-scripts.json"))

(defplan hubot-settings []
  (service-supervisor-config
    :upstart
    (assoc default-upstart
           :service-name "hubot"
           :env ["HUBOT_IRC_NICK=\"hubot\""
                 "HUBOT_IRC_ROOMS=\"#main\""
                 "HUBOT_IRC_SERVER=\"127.0.0.1\""
                 (str "HUBOT_WEBADDR=\"http://" (target-name) ":8080/\"")
                 (str "HUBOT_HOST=\"" (parse-ip (primary-ip (target-node))) "\"")
                 "FILE_BRAIN_PATH=\"/var/lib/hubot\""
                 "EXPRESS_STATIC=\"/var/lib/hubot/data\""]
           :exec "start-stop-daemon --start --chuid hubot --chdir /var/lib/hubot/ --exec /var/lib/hubot/bin/hubot -- --name hubot --adapter irc  >> /var/log/hubot.log 2>&1")
    {}))

(defplan hubot []
  (install-hubot)
  (hubot-conf)
  (hubot-dcc)
  (hubot-setenv)
  (restart-job "hubot"))

(def hubot-server
  (server-spec
   :phases {:settings  hubot-settings
            :configure hubot}))

(defplan upgrade []
  (package-manager :update)
  (package-manager :upgrade))

(def irc-server 
  (group-spec
    "server.irc" 
    :extends [(upstart/server-spec {}) ngircd-server znc-server hubot-server kiwi-server]
    :node-spec (node-spec
                 :image {:image-id :ubuntu-12.04})
    :phases {:bootstrap automated-admin-user
             :configure (with-meta upgrade (execute-with-image-credentials-metadata))}))

(def dev-server
  (group-spec
    "server.dev"
    :extends irc-server
    :count 1
    :roles #{:dev}))

(def prod-server
  (group-spec
    "server.prod"
    :extends irc-server
    :count 2
    :roles #{:prod}))
