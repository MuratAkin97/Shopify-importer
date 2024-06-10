package org.example;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ShopifyImporter {
    private static final String SHOPIFY_API_KEY = System.getenv("SHOPIFY_API_KEY");
    private static final String SHOPIFY_API_SECRET = System.getenv("SHOPIFY_API_SECRET");
    private static final String SHOP_URL = "https://1310ac.myshopify.com/";
    private static final String ACCESS_TOKEN = System.getenv("ACCESS_TOKEN");;
    private static final int MAX_RETRIES = 5;
    private static final int RETRY_DELAY_MS = 2000;
    private static final int THREAD_POOL_SIZE = 10;

    public static void main(String[] args) {
        try {
            ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

            List<JSONObject> products = fetchFirstPageProducts();
            List<Future<JSONObject>> futures = new ArrayList<>();

            for (JSONObject pr : products) {
                futures.add(executorService.submit(() -> {
                    JSONObject item = new JSONObject();
                    item.put("title", pr.getString("title"));
                    item.put("link", SHOP_URL + "products/" + pr.getString("handle"));
                    item.put("description", pr.getString("body_html"));
                    item.put("brand", pr.optString("vendor", ""));
                    item.put("tags", pr.optString("tags", ""));
                    item.put("item_group_id", pr.getLong("id"));
                    item.put("product_type", pr.optString("product_type", ""));

                    // Fetch and calculate availability score
                    double availabilityScore = calculateAvailabilityScore(pr.getLong("id"));
                    item.put("availability_score", availabilityScore);

                    return item;
                }));
            }

            JSONArray formattedProducts = new JSONArray();
            for (Future<JSONObject> future : futures) {
                try {
                    formattedProducts.put(future.get());
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            }

            executorService.shutdown();

            saveProductsToFile(formattedProducts.toString(4));
            System.out.println("Products have been saved to products.json");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static List<JSONObject> fetchFirstPageProducts() throws IOException {
        List<JSONObject> allProducts = new ArrayList<>();
        String apiUrl = SHOP_URL + "admin/api/2021-01/products.json";

        HttpURLConnection conn = null;
        int retries = 0;
        while (retries < MAX_RETRIES) {
            try {
                conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("X-Shopify-Access-Token", ACCESS_TOKEN);
                conn.setRequestProperty("Content-Type", "application/json");

                handleRateLimit(conn);

                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String inputLine;
                StringBuilder content = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }
                in.close();

                JSONObject response = new JSONObject(content.toString());
                JSONArray products = response.getJSONArray("products");

                allProducts.addAll(IntStream.range(0, products.length())
                        .mapToObj(products::getJSONObject)
                        .collect(Collectors.toList()));

                break; // exit the retry loop if successful
            } catch (IOException e) {
                if (retries < MAX_RETRIES - 1) {
                    retries++;
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    System.out.println("Rate-limit reached, retrying after " + RETRY_DELAY_MS + " ms");
                } else {
                    throw e;
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        return allProducts;
    }

    private static double calculateAvailabilityScore(long productId) {
        String apiUrl = SHOP_URL + "admin/api/2021-01/products/" + productId + "/variants.json";
        int retries = 0;
        while (retries < MAX_RETRIES) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("X-Shopify-Access-Token", ACCESS_TOKEN);
                conn.setRequestProperty("Content-Type", "application/json");

                handleRateLimit(conn);

                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String inputLine;
                StringBuilder content = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }
                in.close();
                conn.disconnect();

                JSONObject response = new JSONObject(content.toString());
                JSONArray variants = response.getJSONArray("variants");

                int totalSize = variants.length();
                int sizeInStock = (int) IntStream.range(0, variants.length())
                        .mapToObj(variants::getJSONObject)
                        .filter(variant -> variant.getInt("inventory_quantity") > 0)
                        .count();

                return (double) sizeInStock / totalSize;
            } catch (IOException e) {
                if (retries < MAX_RETRIES - 1) {
                    retries++;
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    System.out.println("Rate-limit reached, retrying after " + RETRY_DELAY_MS + " ms");
                } else {
                    e.printStackTrace();
                    return 0.0;
                }
            }
        }
        return 0.0;
    }

    private static void handleRateLimit(HttpURLConnection conn) throws IOException {
        int responseCode = conn.getResponseCode();
        if (responseCode == 429) {
            String retryAfter = conn.getHeaderField("Retry-After");
            int retryDelay;
            if (retryAfter != null) {
                try {
                    retryDelay = (int) (Float.parseFloat(retryAfter) * 1000);
                } catch (NumberFormatException e) {
                    retryDelay = RETRY_DELAY_MS;
                }
            } else {
                retryDelay = RETRY_DELAY_MS;
            }
            try {
                Thread.sleep(retryDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("Rate limit exceeded, retrying after " + retryDelay + " ms");
        }
    }

    private static void saveProductsToFile(String productsJson) {
        try (FileWriter file = new FileWriter("products.json")) {
            file.write(productsJson);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
