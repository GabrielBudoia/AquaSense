package com.aquasense.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@Slf4j
public class EmailService {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.from:noreply@aquasense.io}")
    private String from;

    @Value("${app.frontend.url:https://app.aquasense.io}")
    private String frontendUrl;

    public boolean isEnabled() {
        return mailSender != null;
    }

    @Async
    public void enviarAlertaCritica(String destino, String projetoNombre,
                                     String componente, String mensagem, Long projetoId) {
        if (!isEnabled()) {
            log.debug("[EMAIL] SMTP no configurado — alerta crítica no enviada a {}", destino);
            return;
        }

        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, "UTF-8");

            helper.setFrom(from);
            helper.setTo(destino);
            helper.setSubject("[AquaSense CRÍTICA] " + componente + " — " + projetoNombre);

            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String link = frontendUrl + "/proyectos/" + projetoId;

            String body = """
                    <html><body style="font-family:Arial,sans-serif;background:#f5f5f5;padding:20px">
                    <div style="background:#fff;border-radius:8px;padding:24px;max-width:600px;margin:auto;
                         border-left:4px solid #e53e3e">
                      <h2 style="color:#e53e3e;margin:0 0 16px 0">Alerta Crítica — AquaSense</h2>
                      <table style="width:100%;border-collapse:collapse">
                        <tr>
                          <td style="padding:8px;background:#fff5f5;font-weight:bold;width:40%%">Proyecto</td>
                          <td style="padding:8px;background:#fff5f5">%s</td>
                        </tr>
                        <tr>
                          <td style="padding:8px;font-weight:bold">Componente</td>
                          <td style="padding:8px">%s</td>
                        </tr>
                        <tr>
                          <td style="padding:8px;background:#fff5f5;font-weight:bold">Mensaje</td>
                          <td style="padding:8px;background:#fff5f5">%s</td>
                        </tr>
                        <tr>
                          <td style="padding:8px;font-weight:bold">Timestamp</td>
                          <td style="padding:8px">%s</td>
                        </tr>
                      </table>
                      <p style="margin:20px 0 0 0">
                        <a href="%s" style="background:#e53e3e;color:#fff;padding:10px 20px;
                           border-radius:4px;text-decoration:none;display:inline-block">
                          Ver Proyecto
                        </a>
                      </p>
                    </div>
                    </body></html>
                    """.formatted(projetoNombre, componente, mensagem, timestamp, link);

            helper.setText(body, true);
            mailSender.send(message);
            log.info("[EMAIL] Alerta crítica enviada a {}: componente={}", destino, componente);
        } catch (Exception e) {
            log.warn("[EMAIL] Fallo al enviar alerta crítica a {}: {}", destino, e.getMessage());
        }
    }
}
