package com.urlshortener.config;

import com.urlshortener.contracts.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public class RequestBodyLimitFilter extends OncePerRequestFilter {
    private static final int MAX_LINK_BODY_SIZE = 8192;
    private static final int MAX_WORKFLOW_BODY_SIZE = 20_000;
    private final ObjectMapper objectMapper;

    public RequestBodyLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !(path.equals(request.getContextPath() + "/api/links")
                || path.startsWith(request.getContextPath() + "/api/orchestration/runs"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int maxBodySize = request.getRequestURI().startsWith(request.getContextPath() + "/api/orchestration/")
                ? MAX_WORKFLOW_BODY_SIZE : MAX_LINK_BODY_SIZE;
        if (request.getContentLengthLong() > maxBodySize) {
            reject(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maxBodySize + 1);
        if (body.length > maxBodySize) {
            reject(response);
            return;
        }
        chain.doFilter(new BufferedRequest(request, body), response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse("Request body too large"));
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    try {
                        if (isFinished()) {
                            listener.onAllDataRead();
                        } else {
                            listener.onDataAvailable();
                        }
                    } catch (IOException exception) {
                        listener.onError(exception);
                    }
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8
                    : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}