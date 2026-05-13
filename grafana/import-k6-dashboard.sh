#!/bin/bash
# k6 공식 대시보드 (Grafana ID: 2587) 를 Grafana에 자동 import합니다.
# docker-compose up 후 Grafana가 완전히 뜬 다음 실행하세요.

GRAFANA_URL="http://admin:admin@localhost:3000"

echo "k6 대시보드 다운로드 중..."
DASHBOARD_JSON=$(curl -sf https://grafana.com/api/dashboards/2587/revisions/latest/download)

if [ -z "$DASHBOARD_JSON" ]; then
  echo "대시보드 다운로드 실패. 네트워크를 확인하세요."
  exit 1
fi

echo "Grafana에 import 중..."
curl -s "$GRAFANA_URL/api/dashboards/import" \
  -H "Content-Type: application/json" \
  -d "{
    \"dashboard\": $DASHBOARD_JSON,
    \"folderId\": 0,
    \"overwrite\": true,
    \"inputs\": [{
      \"name\": \"DS_K6\",
      \"type\": \"datasource\",
      \"pluginId\": \"influxdb\",
      \"value\": \"InfluxDB-k6\"
    }]
  }" | python3 -m json.tool

echo ""
echo "완료: http://localhost:3000 에서 k6 폴더를 확인하세요."
