(ns irc-deploy.core
  (:use [pallet.compute.vmfest :only [add-image]]
        [pallet.compute :only [instantiate-provider]]
        [pallet.api :only [group-spec, node-spec]]
        [pallet.crate :only [defplan get-settings assoc-settings]]
        [pallet.crate.automated-admin-user :only [automated-admin-user]]
        [pallet.actions :only [package package-manager service with-service-restart remote-file directory]]
        [pallet.config-file.format :only [name-values sectioned-properties]]))

(def vmfest (instantiate-provider "vmfest"))
;(add-image vmfest "https://s3.amazonaws.com/vmfest-images/ubuntu-12.04.vdi.gz")

(defn md5 [s]
  (org.apache.commons.codec.digest.DigestUtils/md5Hex s))

(defplan ngircd-conf []
  (let [{:keys [host motd]} (get-settings :irc-server)]
    (remote-file
      "/etc/ngircd/ngircd.conf"
      :user "irc"
      :group "irc"
      :content (sectioned-properties
                 {:global  {:Listen "127.0.0.1"
                            :Name host
                            :MotdPhrase motd
                            ;Keep this setting in sync with PIDFILE in /etc/init.d/ngircd
                            :PidFile "/var/run/ngircd/ngircd.pid"
                            ;Keep this setting in sync with DAEMONUSER in /etc/init.d/ngircd
                            :ServerGID "irc"
                            ;Keep this setting in sync with DAEMONUSER in /etc/init.d/ngircd
                            :ServerUID "irc"
                            ;Not required by server but by RFC!
                            :AdminInfo1 "A private server"
	                    :AdminInfo2 "The Internet"
	                    :AdminEMail (str "admin@" host)}
                  :Options {:PAM "no"
                            :SyslogFacility "local1"}
                  :Limits  {:MaxConnectionsIP 0}}))))

(defplan ngircd []
  (package "ngircd")
  (with-service-restart "ngircd"
    (ngircd-conf)))

(defplan sbnc-conf []
  (let [{:keys [username password]} (get-settings :irc-admin)]
    (remote-file
      "/etc/sbnc/sbnc.conf"
      :user "sbnc"
      :group "sbnc"
      :content (name-values
                 {:system.users username
                  :system.port 6697
                  :system.md5 1}
                 :separator "="))
    
    (directory "/var/lib/sbnc/users/")
    (remote-file
      (format "/var/lib/sbnc/users/%s.conf" username) ; exploit here
      :user "sbnc"
      :group "sbnc"
      :content (name-values
                 {:user.password (md5 password)
                  :user.admin 1
                  :user.nick username
                  :user.server "localhost"
                  :user.port 6667}
                 :separator "="))))

(defplan start-sbnc []
  (remote-file "/etc/default/sbnc" :content "AUTOSTART_SBNC=1")
  (service "sbnc" :action :start))

(defplan sbnc []
  (package "sbnc")
  (sbnc-conf)
  (start-sbnc))

(defplan configure-irc []
  (package-manager :update)
  (ngircd)
  (sbnc))

(defplan settings []
  (assoc-settings :irc-admin  {:username "admin", :password "admin"})
  (assoc-settings :irc-server {:host "irc.example.com", :motd "Welcome!"}))

(def ubuntu-group 
  (group-spec
    "ubuntu-vms" 
    :count 1
    :node-spec (node-spec
                 :image {:os-family :ubuntu
                         :os-64-bit? true}
    :phases {:bootstrap automated-admin-user
             :configure configure-irc
             :settings settings})))
