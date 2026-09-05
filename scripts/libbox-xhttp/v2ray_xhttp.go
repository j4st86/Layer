package option

// V2RayXHTTPOptions is the Layer subset of Xray XHTTP / splithttp settings.
// Official sing-box 1.14 has no XHTTP; Layer patches this type into libbox.
type V2RayXHTTPOptions struct {
	Host          string `json:"host,omitempty"`
	Path          string `json:"path,omitempty"`
	Mode          string `json:"mode,omitempty"`
	XPaddingBytes string `json:"x_padding_bytes,omitempty"`
}
