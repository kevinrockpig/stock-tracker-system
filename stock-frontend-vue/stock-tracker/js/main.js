const { createApp } = Vue;

createApp({
    data() {
        return {
            ticker: '2330.TW',
            loading: false,
            apiUrl: 'https://stock-api-python.vercel.app' 
        }
    },
    methods: {
        async fetchData() {
            this.loading = true;
            try {
                const res = await fetch(`${this.apiUrl}/api/stock/${this.ticker}`);
                if (!res.ok) throw new Error('資料獲取失敗');
                const data = await res.json();

                // 1. 將第一天往前推 7 天，最後一天往後推 7 天，作為 X 軸左右留白
                const startDate = new Date(data.dates[0]);
                startDate.setDate(startDate.getDate() - 7);
                const paddedFirstDate = startDate.toISOString().split('T')[0];

                const endDate = new Date(data.dates[data.dates.length - 1]);
                endDate.setDate(endDate.getDate() + 7);
                const paddedLastDate = endDate.toISOString().split('T')[0];

                const trace = {
                    x: data.dates,
                    y: data.prices,
                    type: 'scatter',
                    mode: 'lines',
                    name: this.ticker,
                    line: { color: '#17BECF' }
                };

                const layout = {
                    title: `${this.ticker} 收盤價走勢`,
                    margin: { t: 50, b: 50, l: 60, r: 30 },
                    xaxis: {
                        type: 'date',
                        // 2. 替換為加了留白的日期範圍
                        // range: [paddedFirstDate, paddedLastDate],
                        rangeselector: {
                            buttons: [
                                { count: 1, label: '1個月', step: 'month', stepmode: 'backward' },
                                { count: 3, label: '3個月', step: 'month', stepmode: 'backward' },
                                { step: 'all', label: '半年(全部)' }
                            ]
                        },
                        rangeslider: { visible: true }
                    },
                    yaxis: {
                        autorange: true,
                        fixedrange: false,
                        // 3. 加上 Y 軸實體分隔線，避免數字直接浮在折線旁
                        showline: true,
                        linewidth: 1,
                        linecolor: '#cccccc'
                    }
                };

                Plotly.newPlot('chart', [trace], layout, { responsive: true });
            } catch (error) {
                alert('獲取資料失敗，請確認代號是否正確或 API 伺服器已啟動。');
            } finally {
                this.loading = false;
            }
        }
    },
    mounted() {
        this.fetchData();
    }
}).mount('#app')