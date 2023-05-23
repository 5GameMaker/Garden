package net.buj.garden;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

import org.reactivestreams.Publisher;

import net.buj.rml.RosepadMod;
import net.buj.rml.Environment;
import net.buj.rml.Game;
import net.buj.rml.events.server.ChatMessageEvent;
import net.buj.rml.events.server.PlayerJoinEvent;
import net.buj.rml.events.server.PlayerLeaveEvent;
import net.buj.rml.events.Event;
import reactor.core.publisher.Mono;

import com.moandjiezana.toml.Toml;

import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Webhook;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.discordjson.json.ChannelData;

class GardenConfig {
    public String token;
    public Long channel;
}

public class Garden extends RosepadMod {
    private String previousPlayer = null;
    private int hookIndex = 0;

    private String escape(String str) {
        return str.replaceAll("[*_`]", "\\$1");
    }

    @Override
    public void pre(Environment env) {}

    @Override
    public void init(Environment env) {
        Path configPath = Paths.get("mods/Garden/config.toml");
        try {
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath.getParent());
                Files.write(configPath, "token = \"bot token here\"\nchannel = channel-id-here\n".getBytes());
                System.out.println("Edit Garden config at " + configPath);
                return;
            }
            GardenConfig config = new Toml().read(configPath.toFile()).to(GardenConfig.class);
            DiscordClient client = DiscordClient.create(config.token);
            GatewayDiscordClient gateway = client.login().block();
            ChannelData data = client.getChannelService().getChannel(config.channel).block();
            if (data.type() != 0) {
                throw new RuntimeException("target channel is not a text channel");
            }
            TextChannel channel = new TextChannel(gateway, data);
            List<Webhook> hooks = new ArrayList<>(channel.getWebhooks().collectList().block());
            while (hooks.size() < 2) {
                hooks.add(channel.createWebhook("dlscord integration hook").block());
            }

            Game.eventLoop.<ChatMessageEvent>listen(ChatMessageEvent.class, event -> {
                if (!event.getUsername().equals(previousPlayer)) {
                    previousPlayer = event.getUsername();
                    hookIndex = (hookIndex + 1) % hooks.size();
                }
                new Thread(() -> {
                    try {
                        hooks.get(hookIndex).execute()
                            .withContent(event.getOriginalMessage())
                            .withUsername(event.getUsername().replace("discord", "dlscord"))
                            .block();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
            }, Event.Priority.LOWEST);

            Game.eventLoop.<PlayerJoinEvent>listen(PlayerJoinEvent.class, event -> {
                new Thread(() -> {
                    try {
                        channel.createMessage(":heavy_plus_sign: | " + event.getUsername() + " joined").block();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
            });

            Game.eventLoop.<PlayerLeaveEvent>listen(PlayerLeaveEvent.class, event -> {
                new Thread(() -> {
                    try {
                        channel.createMessage(":heavy_minus_sign: | " + event.getUsername() + " left").block();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
            });

            new Thread(() -> {
                Publisher<?> gatewayEvent = gateway.on(MessageCreateEvent.class, event -> Mono.just(event.getMessage())
                    .filter(message -> message.getAuthor().isPresent())
                    .filter(message -> !message.getAuthor().get().isBot())
                    .filter(message -> message.getChannelId().asLong() == config.channel)
                    .flatMap(message -> {
                        Game.eventLoop.runOnMainThread(() -> {
                            String content = "[" + escape(message.getAuthor().get().getUsername()) + "] " + message.getContent();
                            System.out.println(content);
                            Game.chat.append(content);
                        });
                        return Mono.just(0);
                    }));
                Mono.when(gatewayEvent).block();
            }, "Garden receive thread").start();

            System.out.println("Garden loaded");
        } catch (Exception e) {
            System.err.println("Failed to load Garden");
            e.printStackTrace();
        }
    }
}
