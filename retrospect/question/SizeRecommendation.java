import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 무신사 사이즈 추천 시스템
 *
 * 풀이 전략:
 * 1. 브랜드별 사이즈 정보를 입력 순서대로 저장 (첫 번째 = 가장 작은 사이즈)
 * 2. 고객 치수가 모든 부위(키, 가슴, 허리)에서 [min, max] 범위에 속하는 사이즈 탐색
 * 3. 매칭되는 사이즈가 없으면 UP / DOWN / MISMATCH 판별
 */
public class SizeRecommendation {

    public static void main(String[] args) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder sb = new StringBuilder();

        // B: 브랜드 수, Q: 고객 수
        String[] firstLine = br.readLine().split(",");
        int B = Integer.parseInt(firstLine[0]);
        int Q = Integer.parseInt(firstLine[1]);

        // 브랜드별 사이즈 목록 (입력 순서 = 사이즈 순서)
        // sizeName, hMin, hMax, cMin, cMax, wMin, wMax
        Map<String, List<String[]>> brands = new LinkedHashMap<>();

        for (int i = 0; i < B; i++) {
            String[] brandLine = br.readLine().split(",");
            String brandName = brandLine[0];
            int sizeCount = Integer.parseInt(brandLine[1]);

            List<String[]> sizes = new ArrayList<>();
            for (int j = 0; j < sizeCount; j++) {
                String[] sizeLine = br.readLine().split(",");
                // [0]=사이즈명, [1]=hMin, [2]=hMax, [3]=cMin, [4]=cMax, [5]=wMin, [6]=wMax
                sizes.add(sizeLine);
            }
            brands.put(brandName, sizes);
        }

        // 고객 요청 처리
        for (int i = 0; i < Q; i++) {
            String[] parts = br.readLine().split(",");
            String wantBrand = parts[0];
            int h = Integer.parseInt(parts[1]);
            int c = Integer.parseInt(parts[2]);
            int w = Integer.parseInt(parts[3]);

            // 규칙 1: 브랜드 존재 여부
            if (!brands.containsKey(wantBrand)) {
                sb.append(wantBrand).append(",UNKNOWN\n");
                continue;
            }

            List<String[]> sizes = brands.get(wantBrand);

            // 규칙 2, 3: 모든 부위가 범위 내인 가장 작은 사이즈 탐색
            String matched = null;
            for (String[] size : sizes) {
                int hMin = Integer.parseInt(size[1]);
                int hMax = Integer.parseInt(size[2]);
                int cMin = Integer.parseInt(size[3]);
                int cMax = Integer.parseInt(size[4]);
                int wMin = Integer.parseInt(size[5]);
                int wMax = Integer.parseInt(size[6]);

                if (h >= hMin && h <= hMax && c >= cMin && c <= cMax && w >= wMin && w <= wMax) {
                    matched = size[0];
                    break; // 입력 순서상 첫 매칭 = 가장 작은 사이즈
                }
            }

            if (matched != null) {
                sb.append(wantBrand).append(",").append(matched).append("\n");
                continue;
            }

            // 규칙 4: 매칭 실패 시 UP / DOWN / MISMATCH 판별
            // 가장 큰 사이즈 = 마지막 원소, 가장 작은 사이즈 = 첫 번째 원소
            String[] largest = sizes.get(sizes.size() - 1);
            String[] smallest = sizes.get(0);

            int lgHMax = Integer.parseInt(largest[2]);
            int lgCMax = Integer.parseInt(largest[4]);
            int lgWMax = Integer.parseInt(largest[6]);

            int smHMin = Integer.parseInt(smallest[1]);
            int smCMin = Integer.parseInt(smallest[3]);
            int smWMin = Integer.parseInt(smallest[5]);

            if (h > lgHMax && c > lgCMax && w > lgWMax) {
                sb.append(wantBrand).append(",UP\n");
            } else if (h < smHMin && c < smCMin && w < smWMin) {
                sb.append(wantBrand).append(",DOWN\n");
            } else {
                sb.append(wantBrand).append(",MISMATCH\n");
            }
        }

        System.out.print(sb);
    }
}
