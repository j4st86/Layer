package v2rayxhttp

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/v2rayhttp"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	sHTTP "github.com/sagernet/sing/protocol/http"

	"golang.org/x/net/http2"
)

const (
	modeAuto      = "auto"
	modeStreamOne = "stream-one"
)

var _ adapter.V2RayClientTransport = (*Client)(nil)

// Client implements Xray XHTTP stream-one (the Reality + mode=auto path).
// Packet-up / stream-up are not implemented: those links fail at config time.
type Client struct {
	ctx        context.Context
	transport  http.RoundTripper
	requestURL url.URL
	host       string
	padMin     int
	padMax     int
}

func NewClient(ctx context.Context, dialer N.Dialer, serverAddr M.Socksaddr, options option.V2RayXHTTPOptions, tlsConfig tls.Config) (adapter.V2RayClientTransport, error) {
	mode := strings.ToLower(strings.TrimSpace(options.Mode))
	if mode == "" {
		mode = modeAuto
	}
	switch mode {
	case modeAuto, modeStreamOne:
	default:
		return nil, E.New("v2ray-xhttp: Layer supports only mode auto/stream-one, got ", options.Mode)
	}
	if tlsConfig == nil {
		return nil, E.New("v2ray-xhttp: TLS is required")
	}
	if len(tlsConfig.NextProtos()) == 0 {
		tlsConfig.SetNextProtos([]string{http2.NextProtoTLS})
	}
	tlsDialer := tls.NewDialer(dialer, tlsConfig)
	transport := &http2.Transport{
		ReadIdleTimeout: 30 * time.Second,
		DialTLSContext: func(ctx context.Context, network, addr string, _ *tls.STDConfig) (net.Conn, error) {
			return tlsDialer.DialTLSContext(ctx, M.ParseSocksaddr(addr))
		},
	}
	path := options.Path
	if path == "" {
		path = "/"
	}
	requestURL := url.URL{
		Scheme: "https",
		Host:   serverAddr.String(),
	}
	if err := sHTTP.URLSetPath(&requestURL, path); err != nil {
		return nil, E.Cause(err, "parse path")
	}
	if !strings.HasPrefix(requestURL.Path, "/") {
		requestURL.Path = "/" + requestURL.Path
	}
	host := options.Host
	if host == "" && tlsConfig.ServerName() != "" {
		host = tlsConfig.ServerName()
	}
	if host == "" {
		host = serverAddr.AddrString()
	}
	padMin, padMax := parsePadding(options.XPaddingBytes)
	return &Client{
		ctx:        ctx,
		transport:  transport,
		requestURL: requestURL,
		host:       host,
		padMin:     padMin,
		padMax:     padMax,
	}, nil
}

func (c *Client) DialContext(ctx context.Context) (net.Conn, error) {
	pipeInReader, pipeInWriter := io.Pipe()
	request := &http.Request{
		Method: http.MethodPost,
		Body:   pipeInReader,
		URL:    &url.URL{Scheme: c.requestURL.Scheme, Host: c.requestURL.Host, Path: c.requestURL.Path, RawPath: c.requestURL.RawPath, RawQuery: c.requestURL.RawQuery},
		Header: make(http.Header),
		Host:   c.host,
	}
	request.Header.Set("Content-Type", "application/grpc")
	// Current Xray validates padding from Referer?x_padding=, not X-Padding.
	request.Header.Set("Referer", c.paddingReferer())
	request = request.WithContext(ctx)
	conn := v2rayhttp.NewLateHTTPConn(pipeInWriter)
	go func() {
		response, err := c.transport.RoundTrip(request)
		if err != nil {
			conn.Setup(nil, err)
			return
		}
		if response.StatusCode != http.StatusOK {
			response.Body.Close()
			conn.Setup(nil, E.New("v2ray-xhttp: unexpected status: ", response.Status))
			return
		}
		conn.Setup(response.Body, nil)
	}()
	return conn, nil
}

func (c *Client) paddingReferer() string {
	referer := url.URL{
		Scheme: "https",
		Host:   c.host,
		Path:   c.requestURL.Path,
	}
	if referer.Path == "" {
		referer.Path = "/"
	}
	referer.RawQuery = "x_padding=" + randomPadding(c.padMin, c.padMax)
	return referer.String()
}

func (c *Client) Close() error {
	c.transport = v2rayhttp.ResetTransport(c.transport)
	return nil
}

func parsePadding(raw string) (int, int) {
	minV, maxV := 100, 1000
	value := strings.TrimSpace(raw)
	if value == "" {
		return minV, maxV
	}
	left, right, ok := strings.Cut(value, "-")
	if !ok {
		if n := atoi(value); n > 0 {
			return n, n
		}
		return minV, maxV
	}
	lo, hi := atoi(left), atoi(right)
	if lo <= 0 || hi <= 0 {
		return minV, maxV
	}
	if hi < lo {
		lo, hi = hi, lo
	}
	return lo, hi
}

func atoi(raw string) int {
	n := 0
	for _, c := range strings.TrimSpace(raw) {
		if c < '0' || c > '9' {
			return 0
		}
		n = n*10 + int(c-'0')
	}
	return n
}

func randomPadding(minV, maxV int) string {
	if minV < 1 {
		minV = 1
	}
	if maxV < minV {
		maxV = minV
	}
	span := maxV - minV + 1
	var buf [8]byte
	_, _ = rand.Read(buf[:])
	n := minV + int(binary.LittleEndian.Uint32(buf[:4])%uint32(span))
	out := make([]byte, n)
	for i := range out {
		out[i] = 'X'
	}
	return string(out)
}
