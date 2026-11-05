package com.ugi.stock.service;

import com.ugi.stock.dto.MyStocksResponse;
import com.ugi.stock.dto.MyStocksResponse.Item;
import com.ugi.stock.dto.StockQuoteResponse;
import com.ugi.stock.entity.UserStock;
import com.ugi.stock.repository.UserStockRepository;
import com.ugi.stock.repository.UserStockRepository.OfficialListing;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.ugi.stock.service.HoldingService.badRequest;

/**
 * 我的自選：使用者自行加入的股票，不限觀察名單。只寫入 user_stocks；行情由 Python 每日更新補抓（加入後等下一次更新才有資料）。
 * 所有方法都以登入者的 userId 過濾，取不到（包括別人的）一律回 404。
 */
@Service
@RequiredArgsConstructor
public class MyStockService {

    public static final int MAX_PER_USER = 30;
    // 全站自行加入、不在觀察名單內的股票最多幾檔，與 Python 的 config.MAX_REQUESTED_STOCKS 相同（Neon 免費 1 GB，每檔約 1.1 MB）
    public static final int MAX_TOTAL = 200;

    private static final Pattern CODE = Pattern.compile("[0-9A-Z]{4,6}");

    private final UserStockRepository userStockRepository;
    private final StockService stockService;

    @Transactional(readOnly = true)
    public MyStocksResponse list(long userId) {
        List<UserStock> mine = userStockRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId);
        Map<String, StockQuoteResponse> quotes = stockService.getLatestQuotes(mine.stream().map(UserStock::getSymbol).toList());
        return new MyStocksResponse(mine.stream().map(s -> item(s, quotes.get(s.getSymbol()))).toList(), MAX_PER_USER);
    }

    @Transactional
    public Item add(long userId, String code) {
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw badRequest("請輸入 4～6 碼的股票代號，例如 2330、00878（不用加 .TW）");
        }
        OfficialListing listing = userStockRepository.findOfficialListing(normalized)
                .orElseThrow(() -> badRequest("查不到代號 " + normalized + "：只支援目前上市、上櫃的股票與 ETF"));
        String symbol = normalized + ("TPEX".equals(listing.getMarket()) ? ".TWO" : ".TW");
        if (userStockRepository.existsByUserIdAndSymbol(userId, symbol)) {
            throw badRequest(symbol + " 已經在我的自選中");
        }
        if (userStockRepository.countByUserId(userId) >= MAX_PER_USER) {
            throw badRequest("我的自選最多 " + MAX_PER_USER + " 檔，請先移除不需要的");
        }
        Map<String, StockQuoteResponse> quotes = stockService.getLatestQuotes(List.of(symbol));
        StockQuoteResponse quote = quotes.get(symbol);
        boolean inWatchlist = quote != null && !quote.groups().isEmpty();
        // 已有人加入或在觀察名單內的股票不增加每日更新的負擔，不受全站上限限制
        if (!inWatchlist && !userStockRepository.existsBySymbol(symbol)
                && userStockRepository.countRequestedOutsideWatchlist() >= MAX_TOTAL) {
            throw badRequest("全站自行加入的股票已達 " + MAX_TOTAL + " 檔上限，暫時無法加入新的股票");
        }
        return item(userStockRepository.saveAndFlush(new UserStock(userId, symbol)), quote);
    }

    @Transactional
    public void remove(long userId, String symbol) {
        UserStock stock = userStockRepository.findByUserIdAndSymbol(userId, symbol)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "我的自選中沒有 " + symbol));
        userStockRepository.delete(stock);
    }

    private Item item(UserStock stock, StockQuoteResponse quote) {
        if (quote == null) {
            // 還沒有資料：名稱取自官方每日資料（查不到時為代號）
            String name = userStockRepository.findOfficialListing(stock.getSymbol().split("\\.")[0])
                    .map(OfficialListing::getName).orElse(stock.getSymbol());
            return new Item(stock.getSymbol(), name, stock.getCreatedAt(), false, false, null);
        }
        return new Item(stock.getSymbol(), quote.name(), stock.getCreatedAt(), quote.tradeDate() != null, !quote.groups().isEmpty(), quote);
    }
}
