package haunt.trust.config;

import java.util.List;
import java.util.ArrayList;

public class BotConfig {

    private int lost_connection_timer;
    private List<Long> autorized_users;
    private List<Long> support_users;
    private GlobalDatabase global_database;

    public int getLost_connection_timer() {
        return lost_connection_timer;
    }

    public void setLost_connection_timer(int lost_connection_timer) {
        this.lost_connection_timer = lost_connection_timer;
    }

    public List<Long> getAutorized_users() {
        if (autorized_users == null) {
            autorized_users = new ArrayList<>();
        }
        return autorized_users;
    }

    public void setAutorized_users(List<Long> autorized_users) {
        this.autorized_users = autorized_users;
    }

    public List<Long> getSupport_users() {
        if (support_users == null) {
            support_users = new ArrayList<>();
        }
        return support_users;
    }

    public void setSupport_users(List<Long> support_users) {
        this.support_users = support_users;
    }

    public GlobalDatabase getGlobal_database() {
        return global_database;
    }

    public void setGlobal_database(GlobalDatabase global_database) {
        this.global_database = global_database;
    }

    public String getGlobalHostname() {
        return global_database.getHostname();
    }

    public String getGlobalPort() {
        return global_database.getPort();
    }

    public String getGlobalUsername() {
        return global_database.getUsername();
    }

    public String getGlobalPassword() {
        return global_database.getPassword();
    }

    public String getGlobalDatabase() {
        return global_database.getDatabase();
    }

    public static class GlobalDatabase {
        private String hostname;
        private String port;
        private String username;
        private String password;
        private String database;

        public String getHostname() {
            return hostname;
        }

        public void setHostname(String hostname) {
            this.hostname = hostname;
        }

        public String getPort() {
            return port;
        }

        public void setPort(String port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }
    }
}
