package derp.rpi.gmail;

import java.io.*;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.Maps;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.common.collect.*;

import derp.rpi.Notify;
import derp.rpi.NotifySource;
import derp.rpi.hardware.StateBuilder;
import derp.rpi.hardware.StateBuilder.Color;
import derp.rpi.hardware.StateBuilder.Digit;

public class GmailNotifier implements NotifySource {

    private static final Logger logger = LoggerFactory.getLogger(GmailNotifier.class);

    private static final String APPLICATION_NAME = "Nixie Notify";

    private static final Map<String, Color> LABEL_COLORS = ImmutableMap.of(
            "CATEGORY_PERSONAL", Color.WHITE,
            "CATEGORY_SOCIAL", Color.BLUE,
            "CATEGORY_UPDATES", Color.YELLOW,
            "CATEGORY_PROMOTIONS", Color.GREEN,
            "IMPORTANT", Color.RED
            );

    private static Gmail initializeGmailService() throws Exception {
        final InputStream in = GmailNotifier.class.getResourceAsStream("/client_secret.json");
        final JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
        final GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(jsonFactory, new InputStreamReader(in));

        final File dataStoreDir = new File(System.getProperty("user.home"), ".credentials/nixie-notify");
        final FileDataStoreFactory dataStoreFactory = new FileDataStoreFactory(dataStoreDir);

        final HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        final GoogleAuthorizationCodeFlow flow =
                new GoogleAuthorizationCodeFlow.Builder(httpTransport, jsonFactory, clientSecrets, Arrays.asList(GmailScopes.GMAIL_READONLY))
                        .setDataStoreFactory(dataStoreFactory)
                        .setAccessType("offline")
                        .build();
        final Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
        logger.info("Credentials saved to {}", dataStoreDir.getAbsolutePath());

        return new Gmail.Builder(httpTransport, jsonFactory, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private Gmail gmail;

    private Gmail getGmailService() {
        if (gmail == null) {
            try {
                gmail = initializeGmailService();
            } catch (Exception e) {
                logger.warn("Failed to initialize GMail", e);
            }
        }

        return gmail;
    }

    private static class CachedMessage {
        public final List<String> labels;

        public CachedMessage(List<String> labels) {
            this.labels = ImmutableList.copyOf(labels);
        }
    }

    private final Map<String, CachedMessage> cache = Maps.newHashMap();

    @Override
    public List<Notify> query() {
        final Gmail service = getGmailService();

        try {
            final ListMessagesResponse unreadMessages = service.users().messages().list("me").setLabelIds(Arrays.asList("UNREAD", "INBOX")).execute();
            final List<Message> messages = unreadMessages.getMessages();

            final Set<String> unreadMessagesIds = Sets.newHashSet();

            if (messages != null)
                messages.stream().map(Message::getId).forEach(unreadMessagesIds::add);

            final Set<String> newMessages = Sets.difference(unreadMessagesIds, cache.keySet());

            for (String newMessageId : newMessages) {
                logger.info("Fetching {}", newMessageId);
                final Message fullMsg = service.users().messages().get("me", newMessageId).execute();
                cache.put(newMessageId, new CachedMessage(fullMsg.getLabelIds()));
            }

            final Multiset<String> labels = TreeMultiset.create();

            for (String msgId : unreadMessagesIds) {
                final CachedMessage msg = cache.get(msgId);
                labels.addAll(msg.labels);
            }

            labels.retainAll(LABEL_COLORS.keySet());

            final List<Notify> notifies = Lists.newArrayList();
            for (Multiset.Entry<String> e : labels.entrySet()) {
                notifies.add(new Notify("gmail:" + e.getElement(), createNotifyPayload(e.getElement(), e.getCount())));
            }

            return notifies;

        } catch (IOException e) {
            logger.warn("Failed to fetch messages from GMail", e);
            return Collections.emptyList();
        }
    }

    private static BitSet createNotifyPayload(String label, int count) {
        final StateBuilder builder = new StateBuilder();

        final Color color = LABEL_COLORS.getOrDefault(label, Color.WHITE);
        builder.setColor(color);

        if (count > 9) {
            builder.setDigit(Optional.of(Digit.D9));
            builder.setLowerDot(true);
            builder.setUpperDot(true);
        } else {
            builder.setDigit(Optional.of(Digit.values()[count]));
        }

        return builder.bakeBits();
    }

    public static void main(String[] args) {
        GmailNotifier notifier = new GmailNotifier();
        final List<Notify> query = notifier.query();
        System.out.println(query);
    }
}
