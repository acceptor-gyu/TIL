import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * 무신사 스토어 픽업 예약 시스템
 *
 * 풀이 전략:
 * 1. 스토어 정보(운영시간, 픽업 한도)와 재고를 Map으로 관리
 * 2. 각 요청을 순서대로 처리하며, 우선순위에 따라 실패 사유 판별
 *    - STORE(1) → TIME(2) → FULL(3) → STOCK(4)
 * 3. 성공 시 재고 차감 + 시간대별 픽업 카운트 증가
 */
public class StorePickupReservation {

    public static void main(String[] args) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder sb = new StringBuilder();

        // S: 스토어 수, R: 예약 요청 수
        String[] firstLine = br.readLine().split(",");
        int S = Integer.parseInt(firstLine[0]);
        int R = Integer.parseInt(firstLine[1]);

        // 스토어 기본 정보: storeId → [오픈시간, 마감시간, 시간당 픽업 한도]
        Map<Integer, int[]> storeInfo = new HashMap<>();
        for (int i = 0; i < S; i++) {
            String[] parts = br.readLine().split(",");
            int id = Integer.parseInt(parts[0]);
            int open = Integer.parseInt(parts[1]);
            int close = Integer.parseInt(parts[2]);
            int limit = Integer.parseInt(parts[3]);
            storeInfo.put(id, new int[]{open, close, limit});
        }

        // 스토어별 상품 재고: storeId → (productId → 수량)
        Map<Integer, Map<String, Integer>> inventory = new HashMap<>();
        for (int i = 0; i < S; i++) {
            String[] parts = br.readLine().split(",");
            int id = Integer.parseInt(parts[0]);
            Map<String, Integer> products = new HashMap<>();
            for (int j = 1; j < parts.length; j++) {
                // 재고 없음("-")이면 빈 맵 유지
                if ("-".equals(parts[j])) {
                    continue;
                }
                String[] productInfo = parts[j].split(":");
                products.put(productInfo[0], Integer.parseInt(productInfo[1]));
            }
            inventory.put(id, products);
        }

        // 스토어별·시간대별 픽업 예약 수: storeId → (hour → count)
        Map<Integer, Map<Integer, Integer>> pickupCounts = new HashMap<>();
        for (int id : storeInfo.keySet()) {
            pickupCounts.put(id, new HashMap<>());
        }

        int successCount = 0;

        // 예약 요청 순서대로 처리
        for (int i = 0; i < R; i++) {
            String[] parts = br.readLine().split(",");
            int reqId = Integer.parseInt(parts[0]);
            int storeId = Integer.parseInt(parts[1]);
            String productId = parts[2];
            int qty = Integer.parseInt(parts[3]);
            String timeStr = parts[4];

            // 희망 시간 파싱 (시간대 = hour 부분만 사용)
            String[] timeParts = timeStr.split(":");
            int hour = Integer.parseInt(timeParts[0]);
            int minute = Integer.parseInt(timeParts[1]);
            int requestMinutes = hour * 60 + minute;

            // 우선순위 1: 존재하지 않는 스토어
            if (!storeInfo.containsKey(storeId)) {
                sb.append(reqId).append(",FAIL,STORE\n");
                continue;
            }

            int[] info = storeInfo.get(storeId);
            int openMinutes = info[0] * 60;
            int closeMinutes = info[1] * 60;
            int limit = info[2];

            // 우선순위 2: 운영 시간 외 (오픈 이상 ~ 마감 미만)
            if (requestMinutes < openMinutes || requestMinutes >= closeMinutes) {
                sb.append(reqId).append(",FAIL,TIME\n");
                continue;
            }

            // 우선순위 3: 해당 시간대 픽업 한도 초과
            Map<Integer, Integer> hourCounts = pickupCounts.get(storeId);
            int currentCount = hourCounts.getOrDefault(hour, 0);
            if (currentCount >= limit) {
                sb.append(reqId).append(",FAIL,FULL\n");
                continue;
            }

            // 우선순위 4: 재고 부족
            Map<String, Integer> products = inventory.get(storeId);
            int stock = products.getOrDefault(productId, 0);
            if (stock < qty) {
                sb.append(reqId).append(",FAIL,STOCK\n");
                continue;
            }

            // 예약 성공: 재고 차감 + 시간대 픽업 수 증가
            products.put(productId, stock - qty);
            hourCounts.put(hour, currentCount + 1);
            successCount++;
            sb.append(reqId).append(",OK\n");
        }

        sb.append(successCount);
        System.out.print(sb);
    }
}
