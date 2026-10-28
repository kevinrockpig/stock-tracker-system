import yfinance as yf
import requests

API_URL = "http://localhost:8081/api/stocks"  # 若你的 Spring Boot 使用 8081 Port 請對應調整

def fetch_and_send(symbol, period="5d"):
    stock = yf.Ticker(symbol)
    hist = stock.history(period=period)

    if hist.empty:
        print(f"未取得 {symbol} 資料")
        return

    for date, row in hist.iterrows():
        payload = {
            "symbol": symbol,
            "tradeDate": date.strftime('%Y-%m-%d'),
            "openPrice": round(row['Open'], 2),
            "highPrice": round(row['High'], 2),
            "lowPrice": round(row['Low'], 2),
            "closePrice": round(row['Close'], 2),
            "volume": int(row['Volume'])
        }

        try:
            res = requests.post(API_URL, json=payload)
            if res.status_code == 200:
                print(f"成功寫入: {payload['tradeDate']} - {payload['closePrice']}")
            else:
                print(f"寫入失敗: {res.status_code}")
        except Exception as e:
            print(f"連線異常: {e}")

if __name__ == "__main__":
    fetch_and_send("2330.TW", period="5d")