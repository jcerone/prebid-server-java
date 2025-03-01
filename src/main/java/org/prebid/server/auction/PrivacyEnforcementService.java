package org.prebid.server.auction;

import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.Geo;
import com.iab.openrtb.request.Regs;
import com.iab.openrtb.request.Site;
import com.iab.openrtb.request.User;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.activity.Activity;
import org.prebid.server.activity.ComponentType;
import org.prebid.server.activity.infrastructure.ActivityInfrastructure;
import org.prebid.server.activity.infrastructure.payload.ActivityInvocationPayload;
import org.prebid.server.activity.infrastructure.payload.impl.ActivityInvocationPayloadImpl;
import org.prebid.server.activity.infrastructure.payload.impl.PrivacyEnforcementServiceActivityInvocationPayload;
import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.auction.model.BidderPrivacyResult;
import org.prebid.server.auction.model.IpAddress;
import org.prebid.server.bidder.BidderCatalog;
import org.prebid.server.execution.Timeout;
import org.prebid.server.geolocation.CountryCodeMapper;
import org.prebid.server.metric.MetricName;
import org.prebid.server.metric.Metrics;
import org.prebid.server.privacy.PrivacyExtractor;
import org.prebid.server.privacy.ccpa.Ccpa;
import org.prebid.server.privacy.gdpr.TcfDefinerService;
import org.prebid.server.privacy.gdpr.VendorIdResolver;
import org.prebid.server.privacy.gdpr.model.PrivacyEnforcementAction;
import org.prebid.server.privacy.gdpr.model.RequestLogInfo;
import org.prebid.server.privacy.gdpr.model.TcfContext;
import org.prebid.server.privacy.gdpr.model.TcfResponse;
import org.prebid.server.privacy.model.Privacy;
import org.prebid.server.privacy.model.PrivacyContext;
import org.prebid.server.proto.openrtb.ext.request.ExtRegs;
import org.prebid.server.proto.openrtb.ext.request.ExtRequest;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtUser;
import org.prebid.server.proto.request.CookieSyncRequest;
import org.prebid.server.settings.model.Account;
import org.prebid.server.settings.model.AccountCcpaConfig;
import org.prebid.server.settings.model.AccountGdprConfig;
import org.prebid.server.settings.model.AccountPrivacyConfig;
import org.prebid.server.settings.model.EnabledForRequestType;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service provides masking for OpenRTB client sensitive information.
 */
public class PrivacyEnforcementService {

    private static final String CATCH_ALL_BIDDERS = "*";
    private static final DecimalFormat ROUND_TWO_DECIMALS =
            new DecimalFormat("###.##", DecimalFormatSymbols.getInstance(Locale.US));

    private static final User EMPTY_USER = User.builder().build();

    private final BidderCatalog bidderCatalog;
    private final PrivacyExtractor privacyExtractor;
    private final TcfDefinerService tcfDefinerService;
    private final ImplicitParametersExtractor implicitParametersExtractor;
    private final IpAddressHelper ipAddressHelper;
    private final Metrics metrics;
    private final CountryCodeMapper countryCodeMapper;
    private final boolean ccpaEnforce;
    private final boolean lmtEnforce;

    public PrivacyEnforcementService(BidderCatalog bidderCatalog,
                                     PrivacyExtractor privacyExtractor,
                                     TcfDefinerService tcfDefinerService,
                                     ImplicitParametersExtractor implicitParametersExtractor,
                                     IpAddressHelper ipAddressHelper,
                                     Metrics metrics,
                                     CountryCodeMapper countryCodeMapper,
                                     boolean ccpaEnforce,
                                     boolean lmtEnforce) {

        this.bidderCatalog = Objects.requireNonNull(bidderCatalog);
        this.privacyExtractor = Objects.requireNonNull(privacyExtractor);
        this.tcfDefinerService = Objects.requireNonNull(tcfDefinerService);
        this.implicitParametersExtractor = Objects.requireNonNull(implicitParametersExtractor);
        this.ipAddressHelper = Objects.requireNonNull(ipAddressHelper);
        this.metrics = Objects.requireNonNull(metrics);
        this.countryCodeMapper = Objects.requireNonNull(countryCodeMapper);
        this.ccpaEnforce = ccpaEnforce;
        this.lmtEnforce = lmtEnforce;
    }

    public Future<PrivacyContext> contextFromBidRequest(AuctionContext auctionContext) {
        final BidRequest bidRequest = auctionContext.getBidRequest();
        final List<String> errors = auctionContext.getPrebidErrors();
        final Account account = auctionContext.getAccount();
        final MetricName requestType = auctionContext.getRequestTypeMetric();
        final Timeout timeout = auctionContext.getTimeout();

        final Privacy privacy = privacyExtractor.validPrivacyFrom(bidRequest, errors);

        final Device device = bidRequest.getDevice();
        final String alpha2CountryCode = resolveAlpha2CountryCode(device);
        final String effectiveIpAddress = resolveIpAddress(device, privacy);

        final AccountGdprConfig accountGdpr = accountGdprConfig(account);
        final String accountId = account.getId();
        final RequestLogInfo requestLogInfo = requestLogInfo(requestType, bidRequest, accountId);

        return tcfDefinerService.resolveTcfContext(
                        privacy,
                        alpha2CountryCode,
                        effectiveIpAddress,
                        accountGdpr,
                        requestType,
                        requestLogInfo,
                        timeout)
                .map(tcfContext -> logWarnings(auctionContext.getDebugWarnings(), tcfContext))
                .map(tcfContext -> PrivacyContext.of(privacy, tcfContext, tcfContext.getIpAddress()));
    }

    private static TcfContext logWarnings(List<String> debugWarnings, TcfContext tcfContext) {
        debugWarnings.addAll(tcfContext.getWarnings());

        return tcfContext;
    }

    private String resolveAlpha2CountryCode(Device device) {
        final Geo geo = device != null ? device.getGeo() : null;
        final String alpha3CountryCode = geo != null ? geo.getCountry() : null;

        return countryCodeMapper.mapToAlpha2(alpha3CountryCode);
    }

    private String resolveIpAddress(Device device, Privacy privacy) {
        final boolean shouldBeMasked = isCoppaMaskingRequired(privacy) || isLmtEnabled(device);

        final String ipV4Address = device != null ? device.getIp() : null;
        if (StringUtils.isNotBlank(ipV4Address)) {
            return shouldBeMasked ? ipAddressHelper.maskIpv4(ipV4Address) : ipV4Address;
        }

        final String ipV6Address = device != null ? device.getIpv6() : null;
        if (StringUtils.isNotBlank(ipV6Address)) {
            return shouldBeMasked ? ipAddressHelper.anonymizeIpv6(ipV6Address) : ipV6Address;
        }

        return null;
    }

    public Future<PrivacyContext> contextFromSetuidRequest(HttpServerRequest httpRequest,
                                                           Account account,
                                                           Timeout timeout) {

        final Privacy privacy = privacyExtractor.validPrivacyFromSetuidRequest(httpRequest);
        final String ipAddress = resolveIpFromRequest(httpRequest);
        final AccountGdprConfig accountGdpr = accountGdprConfig(account);
        final String accountId = account.getId();
        final RequestLogInfo requestLogInfo = requestLogInfo(MetricName.setuid, null, accountId);

        return tcfDefinerService.resolveTcfContext(
                        privacy, ipAddress, accountGdpr, MetricName.setuid, requestLogInfo, timeout)
                .map(tcfContext -> PrivacyContext.of(privacy, tcfContext));
    }

    public Future<PrivacyContext> contextFromCookieSyncRequest(CookieSyncRequest cookieSyncRequest,
                                                               HttpServerRequest httpRequest,
                                                               Account account,
                                                               Timeout timeout) {

        final Privacy privacy = privacyExtractor.validPrivacyFrom(cookieSyncRequest);
        final String ipAddress = resolveIpFromRequest(httpRequest);
        final AccountGdprConfig accountGdpr = accountGdprConfig(account);
        final String accountId = account.getId();
        final RequestLogInfo requestLogInfo = requestLogInfo(MetricName.cookiesync, null, accountId);

        return tcfDefinerService.resolveTcfContext(
                        privacy, ipAddress, accountGdpr, MetricName.cookiesync, requestLogInfo, timeout)
                .map(tcfContext -> PrivacyContext.of(privacy, tcfContext));
    }

    private String resolveIpFromRequest(HttpServerRequest request) {
        final MultiMap headers = request.headers();
        final String host = request.remoteAddress().host();
        final List<String> requestIps = implicitParametersExtractor.ipFrom(headers, host);
        return requestIps.stream()
                .map(ipAddressHelper::toIpAddress)
                .filter(Objects::nonNull)
                .map(IpAddress::getIp)
                .findFirst()
                .orElse(null);
    }

    private static RequestLogInfo requestLogInfo(MetricName requestType, BidRequest bidRequest, String accountId) {
        if (Objects.equals(requestType, MetricName.openrtb2web)) {
            final Site site = bidRequest != null ? bidRequest.getSite() : null;
            final String refUrl = site != null ? site.getRef() : null;
            return RequestLogInfo.of(requestType, refUrl, accountId);
        }

        return RequestLogInfo.of(requestType, null, accountId);
    }

    Future<List<BidderPrivacyResult>> mask(AuctionContext auctionContext,
                                           Map<String, User> bidderToUser,
                                           List<String> bidders,
                                           BidderAliases aliases) {

        final BidRequest bidRequest = auctionContext.getBidRequest();
        final Account account = auctionContext.getAccount();
        final MetricName requestType = auctionContext.getRequestTypeMetric();
        final Device device = bidRequest.getDevice();
        final PrivacyContext privacyContext = auctionContext.getPrivacyContext();

        final Privacy privacy = privacyContext.getPrivacy();

        // For now, COPPA masking all values, so we can omit GDPR masking.
        if (isCoppaMaskingRequired(privacy)) {
            return Future.succeededFuture(maskCoppa(bidderToUser, device));
        }

        updateCcpaMetrics(privacy.getCcpa());
        final Map<String, BidderPrivacyResult> ccpaResult =
                ccpaResult(bidRequest, account, bidders, aliases, device, bidderToUser, privacy, requestType);

        final Set<String> biddersToApplyTcf = new HashSet<>(bidders);
        biddersToApplyTcf.removeAll(ccpaResult.keySet());

        return getBidderToEnforcementAction(privacyContext.getTcfContext(), biddersToApplyTcf, aliases, account)
                .map(bidderToEnforcement -> updatePrivacyMetrics(
                        bidderToEnforcement, aliases, requestType, bidderToUser, device))
                .map(bidderToEnforcement -> getBidderToPrivacyResult(
                        bidderToEnforcement, biddersToApplyTcf, bidderToUser, device))
                .map(gdprResult -> merge(ccpaResult, gdprResult))
                .map(bidderPrivacyResults -> applyActivityRestrictions(bidderPrivacyResults, auctionContext));
    }

    public Future<Map<Integer, PrivacyEnforcementAction>> resultForVendorIds(Set<Integer> vendorIds,
                                                                             TcfContext tcfContext) {

        return tcfDefinerService.resultForVendorIds(vendorIds, tcfContext)
                .map(TcfResponse::getActions);
    }

    private Map<String, BidderPrivacyResult> ccpaResult(BidRequest bidRequest,
                                                        Account account,
                                                        List<String> bidders,
                                                        BidderAliases aliases,
                                                        Device device,
                                                        Map<String, User> bidderToUser,
                                                        Privacy privacy,
                                                        MetricName requestType) {

        return isCcpaEnforced(privacy.getCcpa(), account, requestType)
                ? maskCcpa(extractCcpaEnforcedBidders(bidders, bidRequest, aliases), device, bidderToUser)
                : Collections.emptyMap();
    }

    public boolean isCcpaEnforced(Ccpa ccpa, Account account) {
        final boolean shouldEnforceCcpa = isCcpaEnabled(account);
        return shouldEnforceCcpa && ccpa.isEnforced();
    }

    private boolean isCcpaEnforced(Ccpa ccpa, Account account, MetricName requestType) {
        final boolean shouldEnforceCcpa = isCcpaEnabled(account, requestType);
        return shouldEnforceCcpa && ccpa.isEnforced();
    }

    private Boolean isCcpaEnabled(Account account) {
        final AccountPrivacyConfig accountPrivacyConfig = account.getPrivacy();
        final AccountCcpaConfig accountCcpaConfig =
                accountPrivacyConfig != null ? accountPrivacyConfig.getCcpa() : null;
        final Boolean accountCcpaEnabled = accountCcpaConfig != null ? accountCcpaConfig.getEnabled() : null;

        return ObjectUtils.defaultIfNull(accountCcpaEnabled, ccpaEnforce);
    }

    private boolean isCcpaEnabled(Account account, MetricName requestType) {
        final AccountPrivacyConfig accountPrivacyConfig = account.getPrivacy();
        final AccountCcpaConfig accountCcpaConfig =
                accountPrivacyConfig != null ? accountPrivacyConfig.getCcpa() : null;

        final Boolean accountCcpaEnabled = accountCcpaConfig != null ? accountCcpaConfig.getEnabled() : null;
        if (requestType == null) {
            return ObjectUtils.defaultIfNull(accountCcpaEnabled, ccpaEnforce);
        }

        final EnabledForRequestType enabledForRequestType = accountCcpaConfig != null
                ? accountCcpaConfig.getEnabledForRequestType()
                : null;

        final Boolean enabledForType = enabledForRequestType != null
                ? enabledForRequestType.isEnabledFor(requestType)
                : null;
        return ObjectUtils.firstNonNull(enabledForType, accountCcpaEnabled, ccpaEnforce);
    }

    private Map<String, BidderPrivacyResult> maskCcpa(Set<String> biddersToMask,
                                                      Device device,
                                                      Map<String, User> bidderToUser) {

        return biddersToMask.stream()
                .collect(Collectors.toMap(Function.identity(),
                        bidder -> BidderPrivacyResult.builder()
                                .requestBidder(bidder)
                                .user(maskCcpaUser(bidderToUser.get(bidder)))
                                .device(maskCcpaDevice(device))
                                .build()));
    }

    private User maskCcpaUser(User user) {
        if (user != null) {
            return nullIfEmpty(user.toBuilder()
                    .id(null)
                    .buyeruid(null)
                    .geo(maskGeoDefault(user.getGeo()))
                    .eids(null)
                    .ext(maskUserExt(user.getExt()))
                    .build());
        }
        return null;
    }

    private Device maskCcpaDevice(Device device) {
        if (device != null) {
            return device.toBuilder()
                    .ip(ipAddressHelper.maskIpv4(device.getIp()))
                    .ipv6(ipAddressHelper.anonymizeIpv6(device.getIpv6()))
                    .geo(maskGeoDefault(device.getGeo()))
                    .ifa(null)
                    .macsha1(null).macmd5(null)
                    .dpidsha1(null).dpidmd5(null)
                    .didsha1(null).didmd5(null)
                    .build();
        }
        return null;
    }

    private static boolean isCoppaMaskingRequired(Privacy privacy) {
        return privacy.getCoppa() == 1;
    }

    private List<BidderPrivacyResult> maskCoppa(Map<String, User> bidderToUser, Device device) {
        metrics.updatePrivacyCoppaMetric();

        return bidderToUser.entrySet().stream()
                .map(bidderAndUser -> BidderPrivacyResult.builder()
                        .requestBidder(bidderAndUser.getKey())
                        .user(maskCoppaUser(bidderAndUser.getValue()))
                        .device(maskCoppaDevice(device))
                        .build())
                .toList();
    }

    private User maskCoppaUser(User user) {
        if (user != null) {
            return nullIfEmpty(user.toBuilder()
                    .id(null)
                    .yob(null)
                    .gender(null)
                    .buyeruid(null)
                    .geo(maskGeoForCoppa(user.getGeo()))
                    .eids(null)
                    .ext(maskUserExt(user.getExt()))
                    .build());
        }
        return null;
    }

    private Device maskCoppaDevice(Device device) {
        if (device != null) {
            return device.toBuilder()
                    .ip(ipAddressHelper.maskIpv4(device.getIp()))
                    .ipv6(ipAddressHelper.anonymizeIpv6(device.getIpv6()))
                    .geo(maskGeoForCoppa(device.getGeo()))
                    .ifa(null)
                    .macsha1(null).macmd5(null)
                    .dpidsha1(null).dpidmd5(null)
                    .didsha1(null).didmd5(null)
                    .build();
        }
        return null;
    }

    /**
     * Returns masked for COPPA {@link Geo}.
     */
    private static Geo maskGeoForCoppa(Geo geo) {
        final Geo updatedGeo = geo != null
                ? geo.toBuilder().lat(null).lon(null).metro(null).city(null).zip(null).build()
                : null;
        return updatedGeo == null || updatedGeo.equals(Geo.EMPTY) ? null : updatedGeo;
    }

    /**
     * Returns {@link Future &lt;{@link Map}&lt;{@link String}, {@link PrivacyEnforcementAction}&gt;&gt;},
     * where bidder names mapped to actions for GDPR masking for pbs server.
     */
    private Future<Map<String, PrivacyEnforcementAction>> getBidderToEnforcementAction(TcfContext tcfContext,
                                                                                       Set<String> bidders,
                                                                                       BidderAliases aliases,
                                                                                       Account account) {

        return tcfDefinerService.resultForBidderNames(
                        Collections.unmodifiableSet(bidders),
                        VendorIdResolver.of(aliases, bidderCatalog),
                        tcfContext,
                        accountGdprConfig(account))
                .map(tcfResponse -> mapTcfResponseToEachBidder(tcfResponse, bidders));
    }

    private Set<String> extractCcpaEnforcedBidders(List<String> bidders,
                                                   BidRequest bidRequest,
                                                   BidderAliases aliases) {

        final Set<String> ccpaEnforcedBidders = new HashSet<>(bidders);

        final ExtRequest extBidRequest = bidRequest.getExt();
        final ExtRequestPrebid extRequestPrebid = extBidRequest != null ? extBidRequest.getPrebid() : null;
        final List<String> nosaleBidders = extRequestPrebid != null
                ? ListUtils.emptyIfNull(extRequestPrebid.getNosale())
                : Collections.emptyList();

        if (nosaleBidders.size() == 1 && nosaleBidders.contains(CATCH_ALL_BIDDERS)) {
            ccpaEnforcedBidders.clear();
        } else {
            nosaleBidders.forEach(ccpaEnforcedBidders::remove);
        }

        ccpaEnforcedBidders.removeIf(bidder ->
                !bidderCatalog.bidderInfoByName(aliases.resolveBidder(bidder)).isCcpaEnforced());

        return ccpaEnforcedBidders;
    }

    private static Map<String, PrivacyEnforcementAction> mapTcfResponseToEachBidder(TcfResponse<String> tcfResponse,
                                                                                    Set<String> bidders) {

        final Map<String, PrivacyEnforcementAction> bidderNameToAction = tcfResponse.getActions();
        return bidders.stream()
                .collect(Collectors.toMap(Function.identity(), bidderNameToAction::get));
    }

    private void updateCcpaMetrics(Ccpa ccpa) {
        metrics.updatePrivacyCcpaMetrics(ccpa.isNotEmpty(), ccpa.isEnforced());
    }

    private Map<String, PrivacyEnforcementAction> updatePrivacyMetrics(
            Map<String, PrivacyEnforcementAction> bidderToEnforcement,
            BidderAliases aliases,
            MetricName requestType,
            Map<String, User> bidderToUser,
            Device device) {

        // Metrics should represent real picture of the bidding process, so if bidder request is blocked
        // by privacy then no reason to increment another metrics, like geo masked, etc.
        for (final Map.Entry<String, PrivacyEnforcementAction> bidderEnforcement : bidderToEnforcement.entrySet()) {
            final String bidder = bidderEnforcement.getKey();
            final PrivacyEnforcementAction enforcement = bidderEnforcement.getValue();

            final boolean requestBlocked = enforcement.isBlockBidderRequest();

            final User user = bidderToUser.get(bidder);
            boolean userIdRemoved = enforcement.isRemoveUserIds();
            if (requestBlocked || (userIdRemoved && !shouldMaskUser(user))) {
                userIdRemoved = false;
            }

            boolean geoMasked = enforcement.isMaskGeo();
            if (requestBlocked || (geoMasked && !shouldMaskGeo(user, device))) {
                geoMasked = false;
            }

            final boolean analyticsBlocked = !requestBlocked && enforcement.isBlockAnalyticsReport();

            metrics.updateAuctionTcfMetrics(
                    aliases.resolveBidder(bidder),
                    requestType,
                    userIdRemoved,
                    geoMasked,
                    analyticsBlocked,
                    requestBlocked);
        }

        if (lmtEnforce && isLmtEnabled(device)) {
            metrics.updatePrivacyLmtMetric();
        }

        return bidderToEnforcement;
    }

    /**
     * Returns true if {@link User} has sensitive privacy information that can be masked.
     */
    private static boolean shouldMaskUser(User user) {
        if (user == null) {
            return false;
        }
        if (user.getId() != null || user.getBuyeruid() != null) {
            return true;
        }

        return CollectionUtils.isNotEmpty(user.getEids());
    }

    /**
     * Returns true if {@link User} or {@link Device} has {@link Geo} information that can be masked.
     */
    private static boolean shouldMaskGeo(User user, Device device) {
        return (user != null && user.getGeo() != null) || (device != null && device.getGeo() != null);
    }

    /**
     * Returns {@link Map}&lt;{@link String}, {@link BidderPrivacyResult}&gt;, where bidder name mapped to masked
     * {@link BidderPrivacyResult}. Masking depends on GDPR and COPPA.
     */
    private List<BidderPrivacyResult> getBidderToPrivacyResult(
            Map<String, PrivacyEnforcementAction> bidderToEnforcement,
            Set<String> bidders,
            Map<String, User> bidderToUser,
            Device device) {

        final boolean isLmtEnabled = lmtEnforce && isLmtEnabled(device);

        return bidderToUser.entrySet().stream()
                .filter(entry -> bidders.contains(entry.getKey()))
                .map(bidderUserEntry -> createBidderPrivacyResult(
                        bidderUserEntry.getValue(),
                        device,
                        bidderUserEntry.getKey(),
                        isLmtEnabled,
                        bidderToEnforcement))
                .toList();
    }

    /**
     * Returns {@link BidderPrivacyResult} with GDPR masking.
     */
    private BidderPrivacyResult createBidderPrivacyResult(User user,
                                                          Device device,
                                                          String bidder,
                                                          boolean isLmtEnabled,
                                                          Map<String, PrivacyEnforcementAction> bidderToEnforcement) {

        final PrivacyEnforcementAction privacyEnforcementAction = bidderToEnforcement.get(bidder);
        final boolean blockBidderRequest = privacyEnforcementAction.isBlockBidderRequest();
        final boolean blockAnalyticsReport = privacyEnforcementAction.isBlockAnalyticsReport();
        if (blockBidderRequest) {
            return BidderPrivacyResult.builder()
                    .requestBidder(bidder)
                    .blockedRequestByTcf(true)
                    .blockedAnalyticsByTcf(blockAnalyticsReport)
                    .build();
        }

        final boolean maskGeo = privacyEnforcementAction.isMaskGeo() || isLmtEnabled;
        final boolean maskUserIds = privacyEnforcementAction.isRemoveUserIds() || isLmtEnabled;
        final User maskedUser = maskTcfUser(user, maskUserIds, maskGeo);

        final boolean maskIp = privacyEnforcementAction.isMaskDeviceIp() || isLmtEnabled;
        final boolean maskInfo = privacyEnforcementAction.isMaskDeviceInfo() || isLmtEnabled;
        final Device maskedDevice = maskTcfDevice(device, maskIp, maskGeo, maskInfo);

        return BidderPrivacyResult.builder()
                .requestBidder(bidder)
                .user(maskedUser)
                .device(maskedDevice)
                .blockedAnalyticsByTcf(blockAnalyticsReport)
                .build();
    }

    /**
     * Returns masked {@link User}.
     */
    private User maskTcfUser(User user, boolean maskUserIds, boolean maskGeo) {
        if (user != null) {
            final User.UserBuilder userBuilder = user.toBuilder();

            if (maskGeo) {
                userBuilder.geo(maskGeoDefault(user.getGeo()));
            }

            if (maskUserIds) {
                userBuilder
                        .id(null)
                        .buyeruid(null)
                        .eids(null)
                        .ext(maskUserExt(user.getExt()));
            }

            return nullIfEmpty(userBuilder.build());
        }
        return null;
    }

    /**
     * Returns masked device accordingly for each flag.
     */
    private Device maskTcfDevice(Device device, boolean maskIp, boolean maskGeo, boolean maskInfo) {
        if (device != null) {
            final Device.DeviceBuilder deviceBuilder = device.toBuilder();
            if (maskIp) {
                deviceBuilder
                        .ip(ipAddressHelper.maskIpv4(device.getIp()))
                        .ipv6(ipAddressHelper.anonymizeIpv6(device.getIpv6()));
            }

            if (maskGeo) {
                deviceBuilder.geo(maskGeoDefault(device.getGeo()));
            }

            if (maskInfo) {
                deviceBuilder.ifa(null)
                        .macsha1(null).macmd5(null)
                        .dpidsha1(null).dpidmd5(null)
                        .didsha1(null).didmd5(null);
            }

            return deviceBuilder.build();
        }
        return null;
    }

    /**
     * Returns masked for GDPR {@link Geo} by rounding lon and lat properties.
     */
    private static Geo maskGeoDefault(Geo geo) {
        if (geo != null) {
            return geo.toBuilder()
                    .lat(maskGeoCoordinate(geo.getLat()))
                    .lon(maskGeoCoordinate(geo.getLon()))
                    .build();
        }
        return null;
    }

    /**
     * Returns masked geo coordinate with rounded value to two decimals.
     */
    private static Float maskGeoCoordinate(Float coordinate) {
        return coordinate != null ? Float.valueOf(ROUND_TWO_DECIMALS.format(coordinate)) : null;
    }

    /**
     * Returns masked eids of user ext.
     */
    private static ExtUser maskUserExt(ExtUser userExt) {
        return userExt != null
                ? nullIfEmpty(userExt.toBuilder().digitrust(null).build())
                : null;
    }

    /**
     * Returns null if {@link ExtUser} has no data in case of masking was applied.
     */
    private static ExtUser nullIfEmpty(ExtUser userExt) {
        return userExt.isEmpty() ? null : userExt;
    }

    /**
     * Returns null if {@link User} has no data in case of masking was applied.
     */
    private static User nullIfEmpty(User user) {
        return Objects.equals(user, EMPTY_USER) ? null : user;
    }

    private static boolean isLmtEnabled(Device device) {
        return device != null && Objects.equals(device.getLmt(), 1);
    }

    private List<BidderPrivacyResult> applyActivityRestrictions(List<BidderPrivacyResult> bidderPrivacyResults,
                                                                AuctionContext auctionContext) {

        return bidderPrivacyResults.stream()
                .map(bidderPrivacyResult -> applyActivityRestrictions(bidderPrivacyResult, auctionContext))
                .toList();
    }

    private BidderPrivacyResult applyActivityRestrictions(BidderPrivacyResult bidderPrivacyResult,
                                                          AuctionContext auctionContext) {

        final ActivityInfrastructure activityInfrastructure = auctionContext.getActivityInfrastructure();

        final String bidder = bidderPrivacyResult.getRequestBidder();
        final User user = bidderPrivacyResult.getUser();
        final Device device = bidderPrivacyResult.getDevice();

        final Geo geo = device != null ? device.getGeo() : null;
        final ActivityInvocationPayload activityInvocationPayload =
                PrivacyEnforcementServiceActivityInvocationPayload.of(
                        ActivityInvocationPayloadImpl.of(ComponentType.BIDDER, bidder),
                        geo != null ? geo.getCountry() : null,
                        geo != null ? geo.getRegion() : null,
                        Optional.ofNullable(auctionContext.getBidRequest().getRegs())
                                .map(Regs::getExt)
                                .map(ExtRegs::getGpc)
                                .orElse(null));

        final boolean disallowTransmitUfpd = !activityInfrastructure.isAllowed(
                Activity.TRANSMIT_UFPD, activityInvocationPayload);
        final boolean disallowTransmitGeo = !activityInfrastructure.isAllowed(
                Activity.TRANSMIT_GEO, activityInvocationPayload);

        final User resolvedUser = disallowTransmitUfpd || disallowTransmitGeo
                ? maskUserConsideringActivityRestrictions(user, disallowTransmitUfpd, disallowTransmitGeo)
                : user;
        final Device resolvedDevice = disallowTransmitUfpd || disallowTransmitGeo
                ? maskDeviceConsideringActivityRestrictions(device, disallowTransmitUfpd, disallowTransmitGeo)
                : device;

        return bidderPrivacyResult.toBuilder()
                .user(resolvedUser)
                .device(resolvedDevice)
                .build();
    }

    public User maskUserConsideringActivityRestrictions(User user,
                                                        boolean disallowTransmitUfpd,
                                                        boolean disallowTransmitGeo) {

        if (!(disallowTransmitGeo || disallowTransmitUfpd) || user == null) {
            return user;
        }

        final User.UserBuilder userBuilder = user.toBuilder();

        if (disallowTransmitUfpd) {
            final ExtUser extUser = user.getExt();
            userBuilder
                    .id(null)
                    .buyeruid(null)
                    .yob(null)
                    .gender(null)
                    .data(null)
                    .eids(null)
                    .ext(extUser != null ? nullIfEmpty(extUser.toBuilder().data(null).build()) : null);
        }

        if (disallowTransmitGeo) {
            userBuilder.geo(maskGeoDefault(user.getGeo()));
        }

        return userBuilder.build();
    }

    public Device maskDeviceConsideringActivityRestrictions(Device device,
                                                            boolean disallowTransmitUfpd,
                                                            boolean disallowTransmitGeo) {

        if (!(disallowTransmitGeo || disallowTransmitUfpd)) {
            return device;
        }

        return maskTcfDevice(device, disallowTransmitGeo, disallowTransmitGeo, disallowTransmitUfpd);
    }

    private static List<BidderPrivacyResult> merge(
            Map<String, BidderPrivacyResult> ccpaResult, List<BidderPrivacyResult> gdprResult) {

        final List<BidderPrivacyResult> result = new ArrayList<>(ccpaResult.values());
        result.addAll(gdprResult);
        return result;
    }

    private static AccountGdprConfig accountGdprConfig(Account account) {
        final AccountPrivacyConfig privacyConfig = account.getPrivacy();
        return privacyConfig != null ? privacyConfig.getGdpr() : null;
    }
}
