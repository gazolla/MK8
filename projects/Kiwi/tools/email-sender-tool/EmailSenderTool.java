///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS com.sun.mail:jakarta.mail:2.0.1
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/Log.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java
//SOURCES ../../helpers/SecretClient.java

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.activation.*;
import java.io.File;
import java.io.OutputStream;
import java.util.Map;
import java.util.Properties;

public class EmailSenderTool {

    static final String EVT_TRIGGER = "capability.tool.email.send";
    static final String SOURCE_ID = "email-sender-tool";
    static PluginConfig config;

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        config = PluginConfig.load("plugin.json");
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, EmailSenderTool::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        if (!EVT_TRIGGER.equals(event.type())) return;

        try {
            JsonNode input = KernelEvent.MAPPER.readTree(event.payload()).path("input");
            JsonNode cfg = config.raw().path("config");

            String emaildestino = input.path("emaildestino").asText();
            String titulo = input.path("titulo").asText();
            String msg = input.path("msg").asText();
            String anexo = input.path("anexo").asText("");

            String host = cfg.path("mail.smtp.host").asText("smtp.gmail.com");
            int port = cfg.path("mail.smtp.port").asInt(587);
            String username = cfg.path("mail.smtp.username").asText("");
            String password = SecretClient.get("EMAIL_SMTP_PASSWORD");

            if (password == null || password.isBlank()) {
                PluginBase.publish(KernelEvent.withCorrelation(
                    "capability.error",
                    KernelEvent.MAPPER.writeValueAsString(Map.of(
                        "reason", "Secret EMAIL_SMTP_PASSWORD não configurada. Por favor, forneça a senha de app SMTP via vault."
                    )),
                    SOURCE_ID, event.correlationId(), event.sessionId()
                ), out);
                return;
            }

            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", String.valueOf(port));

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });

            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(username));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emaildestino));
            message.setSubject(titulo);

            if (anexo.isBlank()) {
                message.setText(msg);
            } else {
                MimeMultipart multipart = new MimeMultipart();

                MimeBodyPart messageBodyPart = new MimeBodyPart();
                messageBodyPart.setText(msg);
                multipart.addBodyPart(messageBodyPart);

                File file = new File(anexo);
                if (file.exists()) {
                    MimeBodyPart attachmentPart = new MimeBodyPart();
                    DataSource source = new FileDataSource(file);
                    attachmentPart.setDataHandler(new DataHandler(source));
                    attachmentPart.setFileName(file.getName());
                    multipart.addBodyPart(attachmentPart);
                }

                message.setContent(multipart);
            }

            Transport.send(message);

            PluginBase.publish(KernelEvent.withCorrelation(
                "capability.result",
                KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "result", "Email enviado com sucesso para " + emaildestino
                )),
                SOURCE_ID, event.correlationId(), event.sessionId()
            ), out);

        } catch (Exception e) {
            PluginBase.publish(KernelEvent.withCorrelation(
                "capability.error",
                KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "reason", "Erro ao enviar email: " + e.getMessage()
                )),
                SOURCE_ID, event.correlationId(), event.sessionId()
            ), out);
        }
    }
}