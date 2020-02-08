// Code generated by protoc-gen-go. DO NOT EDIT.
// source: beam_provision_api.proto

package fnexecution_v1

import (
	context "context"
	fmt "fmt"
	pipeline_v1 "github.com/apache/beam/sdks/go/pkg/beam/model/pipeline_v1"
	proto "github.com/golang/protobuf/proto"
	_struct "github.com/golang/protobuf/ptypes/struct"
	grpc "google.golang.org/grpc"
	codes "google.golang.org/grpc/codes"
	status "google.golang.org/grpc/status"
	math "math"
)

// Reference imports to suppress errors if they are not otherwise used.
var _ = proto.Marshal
var _ = fmt.Errorf
var _ = math.Inf

// This is a compile-time assertion to ensure that this generated file
// is compatible with the proto package it is being compiled against.
// A compilation error at this line likely means your copy of the
// proto package needs to be updated.
const _ = proto.ProtoPackageIsVersion3 // please upgrade the proto package

// A request to get the provision info of a SDK harness worker instance.
type GetProvisionInfoRequest struct {
	XXX_NoUnkeyedLiteral struct{} `json:"-"`
	XXX_unrecognized     []byte   `json:"-"`
	XXX_sizecache        int32    `json:"-"`
}

func (m *GetProvisionInfoRequest) Reset()         { *m = GetProvisionInfoRequest{} }
func (m *GetProvisionInfoRequest) String() string { return proto.CompactTextString(m) }
func (*GetProvisionInfoRequest) ProtoMessage()    {}
func (*GetProvisionInfoRequest) Descriptor() ([]byte, []int) {
	return fileDescriptor_92e393e5933c7d6f, []int{0}
}

func (m *GetProvisionInfoRequest) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_GetProvisionInfoRequest.Unmarshal(m, b)
}
func (m *GetProvisionInfoRequest) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_GetProvisionInfoRequest.Marshal(b, m, deterministic)
}
func (m *GetProvisionInfoRequest) XXX_Merge(src proto.Message) {
	xxx_messageInfo_GetProvisionInfoRequest.Merge(m, src)
}
func (m *GetProvisionInfoRequest) XXX_Size() int {
	return xxx_messageInfo_GetProvisionInfoRequest.Size(m)
}
func (m *GetProvisionInfoRequest) XXX_DiscardUnknown() {
	xxx_messageInfo_GetProvisionInfoRequest.DiscardUnknown(m)
}

var xxx_messageInfo_GetProvisionInfoRequest proto.InternalMessageInfo

// A response containing the provision info of a SDK harness worker instance.
type GetProvisionInfoResponse struct {
	Info                 *ProvisionInfo `protobuf:"bytes,1,opt,name=info,proto3" json:"info,omitempty"`
	XXX_NoUnkeyedLiteral struct{}       `json:"-"`
	XXX_unrecognized     []byte         `json:"-"`
	XXX_sizecache        int32          `json:"-"`
}

func (m *GetProvisionInfoResponse) Reset()         { *m = GetProvisionInfoResponse{} }
func (m *GetProvisionInfoResponse) String() string { return proto.CompactTextString(m) }
func (*GetProvisionInfoResponse) ProtoMessage()    {}
func (*GetProvisionInfoResponse) Descriptor() ([]byte, []int) {
	return fileDescriptor_92e393e5933c7d6f, []int{1}
}

func (m *GetProvisionInfoResponse) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_GetProvisionInfoResponse.Unmarshal(m, b)
}
func (m *GetProvisionInfoResponse) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_GetProvisionInfoResponse.Marshal(b, m, deterministic)
}
func (m *GetProvisionInfoResponse) XXX_Merge(src proto.Message) {
	xxx_messageInfo_GetProvisionInfoResponse.Merge(m, src)
}
func (m *GetProvisionInfoResponse) XXX_Size() int {
	return xxx_messageInfo_GetProvisionInfoResponse.Size(m)
}
func (m *GetProvisionInfoResponse) XXX_DiscardUnknown() {
	xxx_messageInfo_GetProvisionInfoResponse.DiscardUnknown(m)
}

var xxx_messageInfo_GetProvisionInfoResponse proto.InternalMessageInfo

func (m *GetProvisionInfoResponse) GetInfo() *ProvisionInfo {
	if m != nil {
		return m.Info
	}
	return nil
}

// Runtime provisioning information for a SDK harness worker instance,
// such as pipeline options, resource constraints and other job metadata
type ProvisionInfo struct {
	// (required) Pipeline options. For non-template jobs, the options are
	// identical to what is passed to job submission.
	PipelineOptions *_struct.Struct `protobuf:"bytes,3,opt,name=pipeline_options,json=pipelineOptions,proto3" json:"pipeline_options,omitempty"`
	// (required) A map from environment_id to artifact retrieval token produced by
	// ArtifactStagingService.CommitManifestResponse.
	RetrievalTokens map[string]string `protobuf:"bytes,6,rep,name=retrieval_tokens,json=retrievalTokens,proto3" json:"retrieval_tokens,omitempty" protobuf_key:"bytes,1,opt,name=key,proto3" protobuf_val:"bytes,2,opt,name=value,proto3"`
	// (optional) The endpoint that the runner is hosting for the SDK to submit
	// status reports to during pipeline execution. This field will only be
	// populated if the runner supports SDK status reports. For more details see
	// https://s.apache.org/beam-fn-api-harness-status
	StatusEndpoint       *pipeline_v1.ApiServiceDescriptor `protobuf:"bytes,7,opt,name=status_endpoint,json=statusEndpoint,proto3" json:"status_endpoint,omitempty"`
	XXX_NoUnkeyedLiteral struct{}                          `json:"-"`
	XXX_unrecognized     []byte                            `json:"-"`
	XXX_sizecache        int32                             `json:"-"`
}

func (m *ProvisionInfo) Reset()         { *m = ProvisionInfo{} }
func (m *ProvisionInfo) String() string { return proto.CompactTextString(m) }
func (*ProvisionInfo) ProtoMessage()    {}
func (*ProvisionInfo) Descriptor() ([]byte, []int) {
	return fileDescriptor_92e393e5933c7d6f, []int{2}
}

func (m *ProvisionInfo) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_ProvisionInfo.Unmarshal(m, b)
}
func (m *ProvisionInfo) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_ProvisionInfo.Marshal(b, m, deterministic)
}
func (m *ProvisionInfo) XXX_Merge(src proto.Message) {
	xxx_messageInfo_ProvisionInfo.Merge(m, src)
}
func (m *ProvisionInfo) XXX_Size() int {
	return xxx_messageInfo_ProvisionInfo.Size(m)
}
func (m *ProvisionInfo) XXX_DiscardUnknown() {
	xxx_messageInfo_ProvisionInfo.DiscardUnknown(m)
}

var xxx_messageInfo_ProvisionInfo proto.InternalMessageInfo

func (m *ProvisionInfo) GetPipelineOptions() *_struct.Struct {
	if m != nil {
		return m.PipelineOptions
	}
	return nil
}

func (m *ProvisionInfo) GetRetrievalTokens() map[string]string {
	if m != nil {
		return m.RetrievalTokens
	}
	return nil
}

func (m *ProvisionInfo) GetStatusEndpoint() *pipeline_v1.ApiServiceDescriptor {
	if m != nil {
		return m.StatusEndpoint
	}
	return nil
}

func init() {
	proto.RegisterType((*GetProvisionInfoRequest)(nil), "org.apache.beam.model.fn_execution.v1.GetProvisionInfoRequest")
	proto.RegisterType((*GetProvisionInfoResponse)(nil), "org.apache.beam.model.fn_execution.v1.GetProvisionInfoResponse")
	proto.RegisterType((*ProvisionInfo)(nil), "org.apache.beam.model.fn_execution.v1.ProvisionInfo")
	proto.RegisterMapType((map[string]string)(nil), "org.apache.beam.model.fn_execution.v1.ProvisionInfo.RetrievalTokensEntry")
}

func init() { proto.RegisterFile("beam_provision_api.proto", fileDescriptor_92e393e5933c7d6f) }

var fileDescriptor_92e393e5933c7d6f = []byte{
	// 400 bytes of a gzipped FileDescriptorProto
	0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0xff, 0x9c, 0x52, 0xcf, 0xce, 0xd2, 0x40,
	0x10, 0x4f, 0x41, 0x3f, 0xe3, 0x7e, 0xfa, 0xb5, 0xd9, 0x90, 0x50, 0x1b, 0x0f, 0x84, 0x68, 0xc2,
	0x69, 0x09, 0x68, 0xa2, 0xf1, 0xa0, 0xa1, 0x81, 0x28, 0x27, 0x4d, 0xf1, 0xe4, 0xa5, 0x96, 0x32,
	0xc5, 0x0d, 0x65, 0x67, 0xdd, 0xdd, 0x36, 0xf2, 0x1c, 0xbe, 0x84, 0x6f, 0xe7, 0x2b, 0x98, 0x6e,
	0x29, 0x01, 0x95, 0x84, 0x70, 0xeb, 0xce, 0xcc, 0xef, 0x5f, 0x67, 0x88, 0xbf, 0x84, 0x64, 0x1b,
	0x4b, 0x85, 0x25, 0xd7, 0x1c, 0x45, 0x9c, 0x48, 0xce, 0xa4, 0x42, 0x83, 0xf4, 0x39, 0xaa, 0x35,
	0x4b, 0x64, 0x92, 0x7e, 0x03, 0x56, 0x0d, 0xb1, 0x2d, 0xae, 0x20, 0x67, 0x99, 0x88, 0xe1, 0x07,
	0xa4, 0x85, 0xe1, 0x28, 0x58, 0x39, 0x0a, 0x5c, 0x10, 0x2b, 0x89, 0x5c, 0x18, 0x5d, 0xe3, 0x82,
	0xa7, 0x6b, 0xc4, 0x75, 0x0e, 0x43, 0xfb, 0x5a, 0x16, 0xd9, 0x50, 0x1b, 0x55, 0xa4, 0xa6, 0xee,
	0xf6, 0x9f, 0x90, 0xee, 0x7b, 0x30, 0x9f, 0x1a, 0xbd, 0xb9, 0xc8, 0x30, 0x82, 0xef, 0x05, 0x68,
	0xd3, 0x5f, 0x11, 0xff, 0xdf, 0x96, 0x96, 0x28, 0x34, 0xd0, 0x0f, 0xe4, 0x1e, 0x17, 0x19, 0xfa,
	0x4e, 0xcf, 0x19, 0xdc, 0x8e, 0x5f, 0xb2, 0x8b, 0xbc, 0xb1, 0x53, 0x2e, 0xcb, 0xd0, 0xff, 0xdd,
	0x22, 0x8f, 0x4f, 0xea, 0x34, 0x24, 0x9e, 0xe4, 0x12, 0x72, 0x2e, 0x20, 0x46, 0x59, 0x61, 0xb5,
	0xdf, 0xb6, 0x3a, 0x5d, 0x56, 0x67, 0x61, 0x4d, 0x16, 0xb6, 0xb0, 0x59, 0x22, 0xb7, 0x01, 0x7c,
	0xac, 0xe7, 0xa9, 0x21, 0x9e, 0x02, 0xa3, 0x38, 0x94, 0x49, 0x1e, 0x1b, 0xdc, 0x80, 0xd0, 0xfe,
	0x4d, 0xaf, 0x3d, 0xb8, 0x1d, 0xcf, 0xaf, 0xf1, 0xca, 0xa2, 0x86, 0xec, 0xb3, 0xe5, 0x9a, 0x09,
	0xa3, 0x76, 0x91, 0xab, 0x4e, 0xab, 0xf4, 0x2b, 0x71, 0xb5, 0x49, 0x4c, 0xa1, 0xe3, 0x66, 0x09,
	0xfe, 0x03, 0x6b, 0xfc, 0xd5, 0x19, 0xd1, 0xc6, 0x76, 0x25, 0x38, 0x91, 0x7c, 0x01, 0xaa, 0xe4,
	0x29, 0x4c, 0x41, 0xa7, 0x8a, 0x4b, 0x83, 0x2a, 0xba, 0xab, 0xf9, 0x66, 0x7b, 0xba, 0x20, 0x24,
	0x9d, 0xff, 0x59, 0xa1, 0x1e, 0x69, 0x6f, 0x60, 0x67, 0xd7, 0xf1, 0x30, 0xaa, 0x3e, 0x69, 0x87,
	0xdc, 0x2f, 0x93, 0xbc, 0x00, 0xbf, 0x65, 0x6b, 0xf5, 0xe3, 0x4d, 0xeb, 0xb5, 0x33, 0xfe, 0xe5,
	0x10, 0xef, 0x90, 0x6e, 0x2f, 0x49, 0x7f, 0x3a, 0xc4, 0xfb, 0x7b, 0xdb, 0xf4, 0xed, 0x85, 0xff,
	0xea, 0xcc, 0x05, 0x05, 0xef, 0xae, 0xc6, 0xd7, 0x67, 0x16, 0x4e, 0xc9, 0xb3, 0x73, 0x0c, 0xc7,
	0x04, 0xe1, 0xa3, 0x03, 0x7c, 0x22, 0xf9, 0x97, 0xbb, 0xa3, 0x6e, 0x5c, 0x8e, 0x96, 0x37, 0xf6,
	0x5c, 0x5e, 0xfc, 0x09, 0x00, 0x00, 0xff, 0xff, 0xec, 0x54, 0x33, 0xc3, 0x5c, 0x03, 0x00, 0x00,
}

// Reference imports to suppress errors if they are not otherwise used.
var _ context.Context
var _ grpc.ClientConn

// This is a compile-time assertion to ensure that this generated file
// is compatible with the grpc package it is being compiled against.
const _ = grpc.SupportPackageIsVersion4

// ProvisionServiceClient is the client API for ProvisionService service.
//
// For semantics around ctx use and closing/ending streaming RPCs, please refer to https://godoc.org/google.golang.org/grpc#ClientConn.NewStream.
type ProvisionServiceClient interface {
	// Get provision information for the SDK harness worker instance.
	GetProvisionInfo(ctx context.Context, in *GetProvisionInfoRequest, opts ...grpc.CallOption) (*GetProvisionInfoResponse, error)
}

type provisionServiceClient struct {
	cc *grpc.ClientConn
}

func NewProvisionServiceClient(cc *grpc.ClientConn) ProvisionServiceClient {
	return &provisionServiceClient{cc}
}

func (c *provisionServiceClient) GetProvisionInfo(ctx context.Context, in *GetProvisionInfoRequest, opts ...grpc.CallOption) (*GetProvisionInfoResponse, error) {
	out := new(GetProvisionInfoResponse)
	err := c.cc.Invoke(ctx, "/org.apache.beam.model.fn_execution.v1.ProvisionService/GetProvisionInfo", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

// ProvisionServiceServer is the server API for ProvisionService service.
type ProvisionServiceServer interface {
	// Get provision information for the SDK harness worker instance.
	GetProvisionInfo(context.Context, *GetProvisionInfoRequest) (*GetProvisionInfoResponse, error)
}

// UnimplementedProvisionServiceServer can be embedded to have forward compatible implementations.
type UnimplementedProvisionServiceServer struct {
}

func (*UnimplementedProvisionServiceServer) GetProvisionInfo(ctx context.Context, req *GetProvisionInfoRequest) (*GetProvisionInfoResponse, error) {
	return nil, status.Errorf(codes.Unimplemented, "method GetProvisionInfo not implemented")
}

func RegisterProvisionServiceServer(s *grpc.Server, srv ProvisionServiceServer) {
	s.RegisterService(&_ProvisionService_serviceDesc, srv)
}

func _ProvisionService_GetProvisionInfo_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(GetProvisionInfoRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(ProvisionServiceServer).GetProvisionInfo(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/org.apache.beam.model.fn_execution.v1.ProvisionService/GetProvisionInfo",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(ProvisionServiceServer).GetProvisionInfo(ctx, req.(*GetProvisionInfoRequest))
	}
	return interceptor(ctx, in, info, handler)
}

var _ProvisionService_serviceDesc = grpc.ServiceDesc{
	ServiceName: "org.apache.beam.model.fn_execution.v1.ProvisionService",
	HandlerType: (*ProvisionServiceServer)(nil),
	Methods: []grpc.MethodDesc{
		{
			MethodName: "GetProvisionInfo",
			Handler:    _ProvisionService_GetProvisionInfo_Handler,
		},
	},
	Streams:  []grpc.StreamDesc{},
	Metadata: "beam_provision_api.proto",
}
