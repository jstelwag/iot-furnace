package handlers;

import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.json.JSONArray;
import org.json.JSONObject;
import redis.clients.jedis.Jedis;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by Jaap on 12-12-2016.
 */
public class RedisHandler extends AbstractHandler {
    @Override
    public void handle(String s, Request request, HttpServletRequest httpServletRequest, HttpServletResponse response) throws IOException, ServletException {
        System.out.println("Redis request");

        Jedis jedis = new Jedis("localhost");
        List<String> all = new ArrayList<>(jedis.keys("*"));
        Collections.sort(all);

        JSONArray redisResponse = new JSONArray();
        for (String key : all) {
            redisResponse.put(new JSONObject().put(key, new JSONObject()
                    .put("value", jedis.get(key))
                    .put("ttl", jedis.ttl(key))));
        }

        IOUtils.closeQuietly(jedis);
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().println(new JSONObject().put("dump", redisResponse).toString(2));
        request.setHandled(true);
    }
}
