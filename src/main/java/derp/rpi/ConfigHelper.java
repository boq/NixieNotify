package derp.rpi;

import java.io.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

public class ConfigHelper {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private static final Logger logger = LoggerFactory.getLogger(ConfigHelper.class);

    public MainConfig readConfig() {
        final MainConfig config;

        final File configFile = new File(System.getProperty("user.home"), ".nixie_notify.json");

        if (configFile.exists()) {
            try {
                config = readConfig(configFile);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read config from file " + configFile, e);
            }
            logger.info("Read config from {}", configFile.getAbsolutePath());
        } else {
            config = new MainConfig();
            try {
                writeConfig(configFile, config);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write config to file " + configFile, e);
            }
            logger.info("Stored default config in {}", configFile.getAbsolutePath());
        }

        return config;
    }

    private MainConfig readConfig(File configFile) throws IOException {
        try (final InputStream input = new FileInputStream(configFile);
                final Reader reader = new InputStreamReader(input, Charsets.UTF_8)) {

            return gson.fromJson(reader, MainConfig.class);
        }
    }

    private void writeConfig(File configFile, MainConfig config) throws IOException {
        try (final OutputStream output = new FileOutputStream(configFile);
                final Writer writer = new OutputStreamWriter(output, Charsets.UTF_8)) {

            final JsonElement jsonConfig = gson.toJsonTree(config);
            gson.toJson(jsonConfig, writer);
        }
    }
}
